package app.organicmaps.launcher.apps;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import java.util.ArrayList;
import java.util.List;

/**
 * Paged app grid adapter (design spec §8).
 *
 * <p>One cell = icon + one-line ellipsized label, nothing else. Icons are requested through
 * {@link AppRepository#iconOrNull}, so binding a cell never blocks on PackageManager: a cold icon
 * shows a themed placeholder and is replaced when the background decode lands.
 */
public class AppGridAdapter extends RecyclerView.Adapter<AppGridAdapter.Holder>
{
  public interface OnAppClickListener
  {
    void onAppClick(@NonNull AppList.Entry entry);

    void onAppLongClick(@NonNull AppList.Entry entry, @NonNull View anchor);
  }

  @NonNull
  private final List<AppList.Entry> mItems = new ArrayList<>();
  @Nullable
  private AppRepository mRepository;
  @Nullable
  private OnAppClickListener mListener;

  public void setRepository(@Nullable AppRepository repository)
  {
    mRepository = repository;
  }

  public void setListener(@Nullable OnAppClickListener listener)
  {
    mListener = listener;
  }

  public void submit(@NonNull List<AppList.Entry> items)
  {
    mItems.clear();
    mItems.addAll(items);
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
  {
    final View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.ts8_app_cell, parent, false);
    return new Holder(v);
  }

  @Override
  public void onBindViewHolder(@NonNull Holder holder, int position)
  {
    holder.bind(mItems.get(position), mRepository, mListener);
  }

  @Override
  public int getItemCount()
  {
    return mItems.size();
  }

  static final class Holder extends RecyclerView.ViewHolder
  {
    @NonNull
    private final ImageView mIcon;
    @NonNull
    private final TextView mLabel;

    Holder(@NonNull View itemView)
    {
      super(itemView);
      mIcon = itemView.findViewById(R.id.ts8_app_icon);
      mLabel = itemView.findViewById(R.id.ts8_app_label);
    }

    void bind(@NonNull AppList.Entry entry, @Nullable AppRepository repository,
              @Nullable OnAppClickListener listener)
    {
      mLabel.setText(entry.label);

      final Drawable cached = repository == null ? null : repository.iconOrNull(entry.packageName, (pkg, drawable) -> {
        // Only apply when the holder still shows this package; recycled holders must not be
        // repainted with a previous row's icon.
        if (entry.packageName.equals(itemView.getTag(R.id.ts8_app_icon)))
          mIcon.setImageDrawable(drawable);
      });
      itemView.setTag(R.id.ts8_app_icon, entry.packageName);
      if (cached != null)
        mIcon.setImageDrawable(cached);
      else
        mIcon.setImageResource(R.drawable.ic_app_placeholder);

      itemView.setOnClickListener(v -> {
        if (listener != null)
          listener.onAppClick(entry);
      });
      itemView.setOnLongClickListener(v -> {
        if (listener != null)
        {
          listener.onAppLongClick(entry, v);
          return true;
        }
        return false;
      });
      itemView.setContentDescription(entry.label);
    }
  }
}
