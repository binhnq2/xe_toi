package app.organicmaps.sdk.esp32;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal, dependency-free JSON object reader for TLP/1 frames.
 *
 * <p>Why not {@code org.json}: the Android SDK's {@code JSONObject} is a stub in JVM unit tests and
 * throws "not mocked", so every frame rule would be untestable without Robolectric. The protocol
 * only needs flat objects of scalars (plus nested objects one level deep for config scopes), so a
 * small parser is both sufficient and testable.
 *
 * <p>It is deliberately strict: a trailing comma, an unclosed brace or a non-JSON literal is a
 * parse error, because a lenient parser would silently accept a malformed frame that the spec says
 * must be rejected.
 */
public final class TlpJson
{
  /** A parsed value: string, number, boolean, null, object or array. */
  public static final class Value
  {
    public final Object raw;

    Value(Object raw)
    {
      this.raw = raw;
    }

    public boolean isObject()
    {
      return raw instanceof Map;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public Map<String, Value> asObject()
    {
      return raw instanceof Map ? (Map<String, Value>) raw : null;
    }

    @Nullable
    public String asString()
    {
      if (raw instanceof String)
        return (String) raw;
      if (raw instanceof Number || raw instanceof Boolean)
        return String.valueOf(raw);
      return null;
    }

    public int asInt(int fallback)
    {
      if (raw instanceof Number)
        return ((Number) raw).intValue();
      if (raw instanceof String)
      {
        try
        {
          return Integer.parseInt((String) raw);
        }
        catch (NumberFormatException e)
        {
          return fallback;
        }
      }
      return fallback;
    }

    public long asLong(long fallback)
    {
      if (raw instanceof Number)
        return ((Number) raw).longValue();
      if (raw instanceof String)
      {
        try
        {
          return Long.parseLong((String) raw);
        }
        catch (NumberFormatException e)
        {
          return fallback;
        }
      }
      return fallback;
    }

    public boolean asBoolean(boolean fallback)
    {
      if (raw instanceof Boolean)
        return (Boolean) raw;
      if (raw instanceof String)
        return Boolean.parseBoolean((String) raw);
      return fallback;
    }
  }

  private final String mSrc;
  private int mPos;

  private TlpJson(@NonNull String src)
  {
    mSrc = src;
  }

  /** Parses a JSON object. Returns null on any syntax error. */
  @Nullable
  public static Map<String, Value> parseObject(@Nullable String src)
  {
    if (src == null)
      return null;
    final TlpJson p = new TlpJson(src.trim());
    try
    {
      final Value v = p.readValue();
      if (v == null || !v.isObject())
        return null;
      p.skipWhitespace();
      if (p.mPos != p.mSrc.length())
        return null;   // trailing garbage
      return v.asObject();
    }
    catch (RuntimeException e)
    {
      return null;
    }
  }

  // ---------------------------------------------------------------- parsing

  @Nullable
  private Value readValue()
  {
    skipWhitespace();
    if (mPos >= mSrc.length())
      return null;
    final char c = mSrc.charAt(mPos);
    switch (c)
    {
      case '{':
        return readObject();
      case '[':
        return readArray();
      case '"':
        return new Value(readString());
      case 't':
        return literal("true", Boolean.TRUE);
      case 'f':
        return literal("false", Boolean.FALSE);
      case 'n':
        return literal("null", null);
      default:
        return readNumber();
    }
  }

  @Nullable
  private Value readObject()
  {
    if (mSrc.charAt(mPos) != '{')
      return null;
    mPos++;
    final Map<String, Value> out = new LinkedHashMap<>();
    skipWhitespace();
    if (mPos < mSrc.length() && mSrc.charAt(mPos) == '}')
    {
      mPos++;
      return new Value(out);
    }
    while (true)
    {
      skipWhitespace();
      if (mPos >= mSrc.length() || mSrc.charAt(mPos) != '"')
        return null;
      final String key = readString();
      if (key == null)
        return null;
      skipWhitespace();
      if (mPos >= mSrc.length() || mSrc.charAt(mPos) != ':')
        return null;
      mPos++;
      final Value v = readValue();
      if (v == null && !isNullLiteralConsumed())
        return null;
      out.put(key, v == null ? new Value(null) : v);
      skipWhitespace();
      if (mPos >= mSrc.length())
        return null;
      final char next = mSrc.charAt(mPos);
      if (next == ',')
      {
        mPos++;
        continue;
      }
      if (next == '}')
      {
        mPos++;
        return new Value(out);
      }
      return null;
    }
  }

  /**
   * {@code readValue()} returns null both for "JSON null" and for "syntax error". The literal
   * reader sets this flag when it consumed an actual {@code null} token so the caller can tell
   * the two apart.
   */
  private boolean mConsumedNullLiteral;

  private boolean isNullLiteralConsumed()
  {
    final boolean v = mConsumedNullLiteral;
    mConsumedNullLiteral = false;
    return v;
  }

  @Nullable
  private Value readArray()
  {
    if (mSrc.charAt(mPos) != '[')
      return null;
    mPos++;
    final java.util.List<Value> out = new java.util.ArrayList<>();
    skipWhitespace();
    if (mPos < mSrc.length() && mSrc.charAt(mPos) == ']')
    {
      mPos++;
      return new Value(out);
    }
    while (true)
    {
      final Value v = readValue();
      if (v == null)
        return null;
      out.add(v);
      skipWhitespace();
      if (mPos >= mSrc.length())
        return null;
      final char next = mSrc.charAt(mPos);
      if (next == ',')
      {
        mPos++;
        continue;
      }
      if (next == ']')
      {
        mPos++;
        return new Value(out);
      }
      return null;
    }
  }

  @Nullable
  private String readString()
  {
    if (mSrc.charAt(mPos) != '"')
      return null;
    mPos++;
    final StringBuilder sb = new StringBuilder();
    while (mPos < mSrc.length())
    {
      final char c = mSrc.charAt(mPos++);
      if (c == '"')
        return sb.toString();
      if (c != '\\')
      {
        sb.append(c);
        continue;
      }
      if (mPos >= mSrc.length())
        return null;
      final char esc = mSrc.charAt(mPos++);
      switch (esc)
      {
        case '"': sb.append('"'); break;
        case '\\': sb.append('\\'); break;
        case '/': sb.append('/'); break;
        case 'b': sb.append('\b'); break;
        case 'f': sb.append('\f'); break;
        case 'n': sb.append('\n'); break;
        case 'r': sb.append('\r'); break;
        case 't': sb.append('\t'); break;
        case 'u':
          if (mPos + 4 > mSrc.length())
            return null;
          try
          {
            sb.append((char) Integer.parseInt(mSrc.substring(mPos, mPos + 4), 16));
          }
          catch (NumberFormatException e)
          {
            return null;
          }
          mPos += 4;
          break;
        default:
          return null;
      }
    }
    return null;   // unterminated
  }

  @Nullable
  private Value literal(@NonNull String word, @Nullable Object value)
  {
    if (!mSrc.startsWith(word, mPos))
      return null;
    mPos += word.length();
    if (value == null)
      mConsumedNullLiteral = true;
    return new Value(value);
  }

  @Nullable
  private Value readNumber()
  {
    final int start = mPos;
    if (mPos < mSrc.length() && (mSrc.charAt(mPos) == '-' || mSrc.charAt(mPos) == '+'))
      mPos++;
    boolean digits = false;
    while (mPos < mSrc.length())
    {
      final char c = mSrc.charAt(mPos);
      if ((c >= '0' && c <= '9'))
      {
        digits = true;
        mPos++;
      }
      else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+')
        mPos++;
      else
        break;
    }
    if (!digits)
      return null;
    final String num = mSrc.substring(start, mPos);
    try
    {
      return new Value(Long.parseLong(num));
    }
    catch (NumberFormatException ignored)
    {
      try
      {
        return new Value(Double.parseDouble(num));
      }
      catch (NumberFormatException e)
      {
        return null;
      }
    }
  }

  private void skipWhitespace()
  {
    while (mPos < mSrc.length())
    {
      final char c = mSrc.charAt(mPos);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r')
        mPos++;
      else
        break;
    }
  }
}
