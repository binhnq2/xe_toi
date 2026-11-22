package app.organicmaps.launcher.signs;

import android.content.Context;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.bookmarks.data.BookmarkCategory;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports sign points as one bookmark category.
 *
 * <p>Why KML and not {@code BookmarkManager.addNewBookmark()}: that call inserts into the framework's
 * "last edited category", a piece of UI state that only exists after a user opened a category by
 * hand. Without it the native side returns null and nothing is created (verified: the category file
 * was written with zero placemarks). {@code loadBookmarksFile(path, temporary)} is public and goes
 * straight to the native KML reader, so it needs no ContentResolver, no FileProvider and no UI
 * state.
 *
 * <p>Signature compatibility: the path layout is verified against
 * {@code data/resources-default/symbols.sdf} expectations only insofar as bookmarks use the standard
 * vector icon set, so no map regeneration is involved.
 */
public final class SignsImporter
{
  private SignsImporter() {}

  /**
   * Replaces the previous import with {@code signs}. Must run on the main thread: the bookmark
   * manager mutates UI-bound state.
   *
   * @return how many placemarks were written; 0 also means "the KML reader rejected the file".
   */
  public static int replace(@NonNull Context context, @NonNull List<SignPoint> signs)
  {
    final BookmarkManager mgr = BookmarkManager.INSTANCE;

    // getCategories() hands back the manager's live list; deleting while walking it throws
    // ConcurrentModificationException, so iterate over a copy.
    for (BookmarkCategory category : new ArrayList<>(mgr.getCategories()))
    {
      if (SignsConfig.CATEGORY_NAME.equals(category.getName()))
        mgr.deleteCategory(category.getId());
    }

    if (signs.isEmpty())
      return 0;

    final File kml;
    try
    {
      kml = writeKml(context, signs);
    }
    catch (IOException e)
    {
      return 0;
    }
    mgr.loadBookmarksFile(kml.getAbsolutePath(), true /* temporary: the KML is only a transport */);
    return signs.size();
  }

  /** Removes the imported category, if any. */
  public static void remove()
  {
    final BookmarkManager mgr = BookmarkManager.INSTANCE;
    for (BookmarkCategory category : new ArrayList<>(mgr.getCategories()))
    {
      if (SignsConfig.CATEGORY_NAME.equals(category.getName()))
        mgr.deleteCategory(category.getId());
    }
  }

  @NonNull
  private static File writeKml(@NonNull Context context, @NonNull List<SignPoint> signs)
      throws IOException
  {
    final StringBuilder sb = new StringBuilder(64 * 1024);
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        .append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n<name>")
        .append(escape(SignsConfig.CATEGORY_NAME))
        .append("</name>\n");

    for (SignPoint p : signs)
    {
      sb.append("<Placemark>\n<name>").append(escape(p.title())).append("</name>\n")
          .append("<description>").append(escape(p.description())).append("</description>\n")
          .append("<Point><coordinates>")
          .append(p.lon).append(',').append(p.lat)
          .append(",0</coordinates></Point>\n</Placemark>\n");
    }
    sb.append("</Document>\n</kml>\n");

    final File file = new File(context.getCacheDir(), "vn-signs.kml");
    try (FileOutputStream out = new FileOutputStream(file))
    {
      out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
    return file;
  }

  @NonNull
  private static String escape(@NonNull String s)
  {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }
}
