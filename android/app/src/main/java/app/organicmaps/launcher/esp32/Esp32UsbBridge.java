package app.organicmaps.launcher.esp32;

import app.organicmaps.R;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.esp32.BridgeConnection;
import app.organicmaps.sdk.esp32.Esp32Config;
import app.organicmaps.sdk.esp32.TlpFrame;
import app.organicmaps.sdk.util.log.Logger;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional USB CDC transport for the ESP32-S3 (design spec §11).
 *
 * <p>The whole class is an opt-in extra: every entry point tolerates "no device" and the app is
 * fully usable without it. That is a hard requirement, not a nicety - the launcher must work with
 * no ESP32 at all.
 *
 * <p>Threading: reads happen on a single background executor, writes are queued to the same
 * executor so a frame is never interleaved mid-line. The state machine
 * ({@link BridgeConnection}) and config ({@link Esp32Config}) are transport-free and already
 * unit-tested.
 *
 * <p>USB permission is requested once per device and remembered by the platform; a denial leaves
 * the bridge {@link BridgeConnection.State#DISCONNECTED} with an actionable reason rather than
 * retrying in a loop.
 */
public class Esp32UsbBridge
{
  private static final String TAG = "Esp32UsbBridge";
  private static final String ACTION_USB_PERMISSION = "app.organicmaps.ts8.USB_PERMISSION";

  /** Must match the firmware's CDC VID/PID, or be overridden in settings. */
  private static final int DEFAULT_VID = 0x303A;   // Espressif Systems
  private static final int DEFAULT_PID = 0x1001;   // USB CDC class as used by the contract

  public interface Listener
  {
    void onBridgeStateChanged(@NonNull BridgeConnection.State state, @Nullable String reason);

    void onFrameReceived(@NonNull TlpFrame frame);
  }

  @NonNull
  private final Context mAppContext;
  @NonNull
  private final BridgeConnection mConnection = new BridgeConnection();
  @NonNull
  private final Esp32Config mConfig = new Esp32Config();
  @NonNull
  private final ExecutorService mIo = Executors.newSingleThreadExecutor();
  @NonNull
  private final Handler mMain = new Handler(Looper.getMainLooper());

  @Nullable
  private UsbDeviceConnection mUsbConnection;
  @Nullable
  private UsbEndpoint mIn;
  @Nullable
  private UsbEndpoint mOut;
  @Nullable
  private Listener mListener;
  private volatile boolean mReading;
  private final ByteArrayOutputStream mLineBuffer = new ByteArrayOutputStream(256);

  private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver()
  {
    @Override
    public void onReceive(Context context, Intent intent)
    {
      final String action = intent.getAction();
      if (ACTION_USB_PERMISSION.equals(action))
      {
        final boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
        if (granted)
          openAttachedDevice();
        else
          notifyState(BridgeConnection.State.DISCONNECTED, mAppContext.getString(R.string.ts8_esp32_usb_denied));
      }
      else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action))
      {
        requestPermissionAndOpen();
      }
      else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action))
      {
        close();
        notifyState(BridgeConnection.State.DISCONNECTED, mAppContext.getString(R.string.ts8_esp32_detached));
      }
    }
  };

  public Esp32UsbBridge(@NonNull Context context)
  {
    mAppContext = context.getApplicationContext();
  }

  public void setListener(@Nullable Listener listener)
  {
    mListener = listener;
  }

  @NonNull
  public BridgeConnection connection()
  {
    return mConnection;
  }

  @NonNull
  public Esp32Config config()
  {
    return mConfig;
  }

  /** Starts listening for attach/detach and tries to connect to an already-attached device. */
  public void start()
  {
    final IntentFilter filter = new IntentFilter();
    filter.addAction(ACTION_USB_PERMISSION);
    filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
    filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    if (Build.VERSION.SDK_INT >= 33)
      mAppContext.registerReceiver(mUsbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    else
      mAppContext.registerReceiver(mUsbReceiver, filter);
    requestPermissionAndOpen();
  }

  public void stop()
  {
    try
    {
      mAppContext.unregisterReceiver(mUsbReceiver);
    }
    catch (Exception ignored)
    {
      // Already unregistered.
    }
    close();
  }

  private void requestPermissionAndOpen()
  {
    final UsbManager manager = (UsbManager) mAppContext.getSystemService(Context.USB_SERVICE);
    if (manager == null)
      return;

    final UsbDevice device = findDevice(manager);
    if (device == null)
    {
      notifyState(BridgeConnection.State.DISCONNECTED, mAppContext.getString(R.string.ts8_esp32_no_device));
      return;
    }

    if (!manager.hasPermission(device))
    {
      final PendingIntent pi = PendingIntent.getBroadcast(
          mAppContext, 0, new Intent(ACTION_USB_PERMISSION).setPackage(mAppContext.getPackageName()),
          PendingIntent.FLAG_IMMUTABLE);
      manager.requestPermission(device, pi);
      return;
    }
    openDevice(manager, device);
  }

  @Nullable
  private static UsbDevice findDevice(@NonNull UsbManager manager)
  {
    final HashMap<String, UsbDevice> devices = manager.getDeviceList();
    for (Map.Entry<String, UsbDevice> e : devices.entrySet())
    {
      final UsbDevice d = e.getValue();
      if (d.getVendorId() == DEFAULT_VID)
        return d;
    }
    return null;
  }

  private void openAttachedDevice()
  {
    final UsbManager manager = (UsbManager) mAppContext.getSystemService(Context.USB_SERVICE);
    if (manager == null)
      return;
    final UsbDevice device = findDevice(manager);
    if (device != null && manager.hasPermission(device))
      openDevice(manager, device);
  }

  private void openDevice(@NonNull UsbManager manager, @NonNull UsbDevice device)
  {
    UsbInterface cdc = null;
    for (int i = 0; i < device.getInterfaceCount(); i++)
    {
      final UsbInterface itf = device.getInterface(i);
      if (itf.getInterfaceClass() == 10 /* USB_CLASS_CDC_DATA */)
      {
        cdc = itf;
        break;
      }
    }
    if (cdc == null)
    {
      notifyState(BridgeConnection.State.DISCONNECTED, mAppContext.getString(R.string.ts8_esp32_no_cdc));
      return;
    }

    final UsbDeviceConnection conn = manager.openDevice(device);
    if (conn == null || !conn.claimInterface(cdc, true))
    {
      notifyState(BridgeConnection.State.DISCONNECTED, "Cannot open USB connection");
      return;
    }

    UsbEndpoint in = null;
    UsbEndpoint out = null;
    for (int i = 0; i < cdc.getEndpointCount(); i++)
    {
      final UsbEndpoint ep = cdc.getEndpoint(i);
      if (ep.getDirection() == android.hardware.usb.UsbConstants.USB_DIR_IN)
        in = ep;
      else
        out = ep;
    }
    if (in == null || out == null)
    {
      conn.close();
      notifyState(BridgeConnection.State.DISCONNECTED, "CDC interface is missing an endpoint");
      return;
    }

    mUsbConnection = conn;
    mIn = in;
    mOut = out;
    mConnection.onAttached(android.os.SystemClock.elapsedRealtime());
    notifyState(BridgeConnection.State.HANDSHAKING, null);
    startReading();
  }

  private void startReading()
  {
    mReading = true;
    mIo.execute(() -> {
      final UsbDeviceConnection conn = mUsbConnection;
      final UsbEndpoint in = mIn;
      if (conn == null || in == null)
        return;

      final byte[] buffer = new byte[512];
      while (mReading)
      {
        final int read = conn.bulkTransfer(in, buffer, buffer.length, 500);
        if (read < 0)
          continue;
        synchronized (mLineBuffer)
        {
          for (int i = 0; i < read; i++)
          {
            final byte b = buffer[i];
            if (b == '\n')
            {
              final String line = mLineBuffer.toString(java.nio.charset.StandardCharsets.UTF_8);
              mLineBuffer.reset();
              if (!line.isEmpty())
                handleLine(line);
            }
            else if (mLineBuffer.size() < TlpFrame.MAX_BYTES * 2)
            {
              // Guard against a peer that never sends a newline.
              mLineBuffer.write(b);
            }
            else
            {
              mLineBuffer.reset();
            }
          }
        }
      }
    });
  }

  private void handleLine(@NonNull String line)
  {
    final long now = android.os.SystemClock.elapsedRealtime();
    final TlpFrame frame = mConnection.onFrame(line, now);
    if (frame == null)
    {
      // Malformed or duplicate: logged redacted, never echoed back.
      Logger.w(TAG, "Dropped frame: " + mConnection.lastError() + " " + TlpFrame.redact(line));
      return;
    }
    if (frame.isHello())
      mConnection.resetBackoff();
    final Listener l = mListener;
    if (l != null)
      mMain.post(() -> l.onFrameReceived(frame));
  }

  /** Sends a frame on the IO thread. Returns false when there is no device. */
  public boolean send(@NonNull String type, @NonNull String bodyJson)
  {
    final UsbDeviceConnection conn = mUsbConnection;
    final UsbEndpoint out = mOut;
    if (conn == null || out == null)
      return false;

    final int seq = mConnection.nextSequence();
    final String line = TlpFrame.encode(seq, android.os.SystemClock.elapsedRealtime(), type, bodyJson) + "\n";
    final byte[] bytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    mIo.execute(() -> {
      try
      {
        conn.bulkTransfer(out, bytes, bytes.length, 500);
      }
      catch (Exception e)
      {
        Logger.w(TAG, "Write failed: " + e.getMessage());
      }
    });
    return true;
  }

  /** Called periodically by the owning screen to drive heartbeat and staleness. */
  public void tick()
  {
    final long now = android.os.SystemClock.elapsedRealtime();
    mConnection.tick(now);
    if (mConnection.shouldSendHeartbeat(now))
    {
      send(TlpFrame.TYPE_PING, "");
      mConnection.onHeartbeatSent(now);
    }
    final Listener l = mListener;
    if (l != null)
      mMain.post(() -> l.onBridgeStateChanged(mConnection.state(), null));
  }

  private void close()
  {
    mReading = false;
    final UsbDeviceConnection conn = mUsbConnection;
    mUsbConnection = null;
    mIn = null;
    mOut = null;
    synchronized (mLineBuffer)
    {
      mLineBuffer.reset();
    }
    if (conn != null)
    {
      try
      {
        conn.close();
      }
      catch (Exception ignored)
      {
        // Port already gone.
      }
    }
    mConnection.onDetached();
  }

  private void notifyState(@NonNull BridgeConnection.State state, @Nullable String reason)
  {
    final Listener l = mListener;
    if (l != null)
      mMain.post(() -> l.onBridgeStateChanged(state, reason));
  }

  /** Never shows diagnostics on Home or Navigation; callers are Settings/Diagnostics only. */
  @NonNull
  public String diagnosticsSummary()
  {
    return "state=" + mConnection.state()
        + " rev=" + mConfig.revision()
        + " hash=" + mConfig.hash()
        + " lastError=" + (mConnection.lastError() == null ? "-" : mConnection.lastError());
  }
}
