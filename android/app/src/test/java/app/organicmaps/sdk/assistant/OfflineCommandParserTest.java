package app.organicmaps.sdk.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Phase 8 gate: offline command fixtures, unsafe/unknown intent, confirmation requirement.
 */
public class OfflineCommandParserTest
{
  @Test
  public void recognisesVietnameseCommands()
  {
    assertEquals(OfflineCommandParser.Kind.OPEN_MAP, OfflineCommandParser.parse("mở bản đồ").kind);
    assertEquals(OfflineCommandParser.Kind.NAVIGATE_HOME, OfflineCommandParser.parse("dẫn đường về nhà").kind);
    assertEquals(OfflineCommandParser.Kind.STOP_NAVIGATION, OfflineCommandParser.parse("dừng dẫn đường").kind);
    assertEquals(OfflineCommandParser.Kind.REPEAT_MANEUVER, OfflineCommandParser.parse("nhắc lại").kind);
    assertEquals(OfflineCommandParser.Kind.MUTE_GUIDANCE, OfflineCommandParser.parse("tắt hướng dẫn").kind);
    assertEquals(OfflineCommandParser.Kind.ZOOM_IN, OfflineCommandParser.parse("phóng to").kind);
    assertEquals(OfflineCommandParser.Kind.MEDIA_NEXT, OfflineCommandParser.parse("bài tiếp").kind);
  }

  /** ASR often drops diacritics; the parser must still match. */
  @Test
  public void matchesWithoutDiacritics()
  {
    assertEquals(OfflineCommandParser.Kind.NAVIGATE_HOME, OfflineCommandParser.parse("dan duong ve nha").kind);
    assertEquals(OfflineCommandParser.Kind.STOP_NAVIGATION, OfflineCommandParser.parse("dung dan duong").kind);
  }

  @Test
  public void recognisesEnglishCommands()
  {
    assertEquals(OfflineCommandParser.Kind.OPEN_MAP, OfflineCommandParser.parse("open map").kind);
    assertEquals(OfflineCommandParser.Kind.STOP_NAVIGATION, OfflineCommandParser.parse("stop navigation").kind);
    assertEquals(OfflineCommandParser.Kind.MEDIA_PLAY_PAUSE, OfflineCommandParser.parse("pause").kind);
  }

  /** Anything outside the allowlist is UNKNOWN, never a guess. */
  @Test
  public void unknownPhraseIsRefused()
  {
    assertEquals(OfflineCommandParser.Kind.UNKNOWN,
                 OfflineCommandParser.parse("mở cửa sổ trời").kind);
    assertEquals(OfflineCommandParser.Kind.UNKNOWN, OfflineCommandParser.parse("").kind);
    assertEquals(OfflineCommandParser.Kind.UNKNOWN, OfflineCommandParser.parse(null).kind);
    assertFalse(OfflineCommandParser.isAllowed(OfflineCommandParser.Kind.UNKNOWN));
  }

  /** Navigation and device-state actions must be confirmable, per the spec. */
  @Test
  public void consequentialActionsRequireConfirmation()
  {
    assertTrue(OfflineCommandParser.parse("dẫn đường về nhà").consequential);
    assertTrue(OfflineCommandParser.parse("dừng dẫn đường").consequential);
    assertTrue(OfflineCommandParser.parse("về màn hình chính").consequential);
  }

  /** Read-only actions must not nag with a confirmation. */
  @Test
  public void benignActionsNeedNoConfirmation()
  {
    assertFalse(OfflineCommandParser.parse("mở bản đồ").consequential);
    assertFalse(OfflineCommandParser.parse("phóng to").consequential);
    assertFalse(OfflineCommandParser.parse("bài tiếp").consequential);
  }

  /** Longest phrase wins so "next track" is not shadowed by a shorter table entry. */
  @Test
  public void longestMatchWins()
  {
    assertEquals(OfflineCommandParser.Kind.MEDIA_NEXT,
                 OfflineCommandParser.parse("please next track now").kind);
  }

  /** Extra words around a known verb phrase still resolve. */
  @Test
  public void extraWordsAroundPhraseStillResolve()
  {
    assertEquals(OfflineCommandParser.Kind.STOP_NAVIGATION,
                 OfflineCommandParser.parse("hey, stop navigation please").kind);
  }

  @Test
  public void normaliseCollapsesWhitespaceAndCase()
  {
    assertEquals("stop navigation", OfflineCommandParser.normalise("  STOP   Navigation  "));
    assertEquals("dan duong", OfflineCommandParser.normalise("Dẫn  Đường"));
  }
}
