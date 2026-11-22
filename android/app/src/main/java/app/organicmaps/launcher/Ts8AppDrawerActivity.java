package app.organicmaps.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.base.BaseMwmFragmentActivity;
import app.organicmaps.launcher.apps.AppGridAdapter;
import app.organicmaps.launcher.apps.AppList;
import app.organicmaps.launcher.apps.AppRepository;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * App drawer (design spec §8).
 *
 * <p>Five columns by two rows at 1024×600 is a GridLayoutManager span count, not a fixed grid: the
 * page is two rows tall and scrolls horizontally inside a pager-like layout, so the same adapter
 * serves 1024×600 and 1280×720 without a second layout.
 *
 * <p>The repository is reused across opens (held in {@link LauncherServices}) so a warm open never
 * queries PackageManager; package broadcasts invalidate it.
 */
public class Ts8AppDrawerActivity extends BaseMwmFragmentActivity implements AppRepository.Callback,
                                                                         AppGridAdapter.OnAppClickListener
{
  private static final int COLUMNS = 5;
  private static final int ROWS = 2;

  @Nullable
  private RecyclerView mGrid;
  @Nullable
  private EditText mSearch;
  @Nullable
  private TextView mEmpty;
  @Nullable
  private AppGridAdapter mAdapter;
  @Nullable
  private BroadcastReceiver mPackageReceiver;
  @Nullable
  private List<AppList.Entry> mAll = Collections.emptyList();

  @Override
  protected int getContentLayoutResId()
  {
    return R.layout.activity_ts8_drawer;
  }

  @Override
  protected void onSafeCreate(@Nullable Bundle savedInstanceState)
  {
    super.onSafeCreate(savedInstanceState);

    mGrid = findViewById(R.id.ts8_drawer_grid);
    mSearch = findViewById(R.id.ts8_drawer_search);
    mEmpty = findViewById(R.id.ts8_drawer_empty);

    mAdapter = new AppGridAdapter();
    mAdapter.setRepository(LauncherServices.apps(this));
    mAdapter.setListener(this);

    if (mGrid != null)
    {
      // Two rows per page: a horizontal-agnostic grid with a fixed span keeps cells the same size
      // on both reference resolutions.
      final GridLayoutManager lm = new GridLayoutManager(this, COLUMNS);
      lm.setSpanCount(COLUMNS);
      mGrid.setLayoutManager(lm);
      mGrid.setAdapter(mAdapter);
      mGrid.setHasFixedSize(true);
    }

    if (mSearch != null)
      mSearch.addTextChangedListener(new TextWatcher()
      {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s)
        {
          applyFilter(s == null ? "" : s.toString());
        }
      });

    // Warm path: draw the cached index immediately, then refresh in the background.
    final List<AppList.Entry> cached = LauncherServices.apps(this).cachedIndex();
    if (cached != null)
      onAppsLoaded(cached);
    else if (mEmpty != null)
      mEmpty.setText(R.string.ts8_drawer_loading);

    LauncherServices.apps(this).load(this);

    registerPackageReceiver();
  }

  private void applyFilter(@NonNull String query)
  {
    if (mAdapter == null)
      return;
    final List<AppList.Entry> filtered = AppList.search(mAll, query);
    mAdapter.submit(filtered);
    if (mEmpty != null)
      mEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    if (mEmpty != null && filtered.isEmpty())
      mEmpty.setText(R.string.ts8_drawer_empty);
  }

  @Override
  public void onAppsLoaded(@NonNull List<AppList.Entry> apps)
  {
    mAll = apps;
    if (mEmpty != null)
      mEmpty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
    final String query = mSearch == null || mSearch.getText() == null ? "" : mSearch.getText().toString();
    applyFilter(query);
  }

  @Override
  public void onAppClick(@NonNull AppList.Entry entry)
  {
    if (!LauncherServices.apps(this).launch(entry.packageName) && mEmpty != null)
    {
      mEmpty.setVisibility(View.VISIBLE);
      mEmpty.setText(getString(R.string.ts8_drawer_empty));
    }
  }

  @Override
  public void onAppLongClick(@NonNull AppList.Entry entry, @NonNull View anchor)
  {
    // v1: a simple menu with Pin/Unpin, Hide, App info. Freeform drag is explicitly out of scope.
    final android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
    final android.view.MenuItem pinItem = menu.getMenu().add(entry.pinned ? R.string.ts8_drawer_unpin
                                                                         : R.string.ts8_drawer_pin);
    final android.view.MenuItem hideItem = menu.getMenu().add(R.string.ts8_drawer_hide);
    final android.view.MenuItem infoItem = menu.getMenu().add(R.string.ts8_drawer_app_info);
    menu.setOnMenuItemClickListener(item -> {
      if (item == pinItem)
      {
        LauncherServices.setPinned(this, entry.packageName, !entry.pinned);
        LauncherServices.apps(this).load(this);
        return true;
      }
      if (item == hideItem)
      {
        LauncherServices.setHidden(this, entry.packageName, true);
        LauncherServices.apps(this).load(this);
        return true;
      }
      if (item == infoItem)
      {
        try
        {
          startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                   android.net.Uri.parse("package:" + entry.packageName)));
        }
        catch (Exception ignored)
        {
          // No settings activity on this device; the menu item is a no-op rather than a crash.
        }
        return true;
      }
      return false;
    });
    menu.show();
  }

  private void registerPackageReceiver()
  {
    final IntentFilter filter = new IntentFilter();
    filter.addAction(Intent.ACTION_PACKAGE_ADDED);
    filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
    filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
    filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
    filter.addDataScheme("package");

    mPackageReceiver = new BroadcastReceiver()
    {
      @Override
      public void onReceive(Context context, Intent intent)
      {
        // Only refresh on the four broadcasts the spec lists; never on resume.
        LauncherServices.apps(Ts8AppDrawerActivity.this).invalidate();
        LauncherServices.apps(Ts8AppDrawerActivity.this).load(Ts8AppDrawerActivity.this);
      }
    };
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(mPackageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    else
      registerReceiver(mPackageReceiver, filter);
  }

  @Override
  protected void onSafeDestroy()
  {
    if (mPackageReceiver != null)
    {
      try
      {
        unregisterReceiver(mPackageReceiver);
      }
      catch (Exception ignored)
      {
        // Receiver already gone.
      }
      mPackageReceiver = null;
    }
    if (mAdapter != null)
      mAdapter.setListener(null);
    super.onSafeDestroy();
  }

  @NonNull
  static Locale deviceLocale(@NonNull Context context)
  {
    return context.getResources().getConfiguration().getLocales().get(0);
  }
}
