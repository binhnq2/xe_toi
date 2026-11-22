package app.organicmaps.maplayer;

import app.organicmaps.BuildConfig;
import app.organicmaps.sdk.maplayer.Mode;
import java.util.ArrayList;
import java.util.List;

public class LayersUtils
{
  public static List<Mode> getAvailableLayers()
  {
    List<Mode> availableLayers = new ArrayList<>();

    // TS8 NAV keeps only what car navigation needs. Outdoors/hiking styling, contour
    // lines (isolines) and the subway scheme are removed from the layer menu
    // (PROMPT Phase 1: hiking/cycling UI, contours/elevation, transit).
    if (!BuildConfig.TS8_LITE)
    {
      availableLayers.add(Mode.OUTDOORS);
      availableLayers.add(Mode.ISOLINES);
      availableLayers.add(Mode.SUBWAY);
    }

    return availableLayers;
  }
}
