/*
 * Minecraft OpenType Font Support Mod
 *
 * Copyright (C) 2012 Wojciech Stryjewski <thvortex@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>..
 */

package net.minecraft.src.betterfonts;

import net.minecraft.src.RenderEngine;
import net.minecraft.src.Tessellator;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.awt.font.GlyphVector;
import java.awt.Font;
import java.awt.Point;
import java.text.Bidi;
import org.lwjgl.opengl.GL11;

/**
 * The StringCache is the public interface for rendering of all Unicode strings using OpenType fonts. It caches the glyph layout
 * of individual strings, and it uses a GlyphCache instance to cache the pre-rendered images for individual glyphs. Once a string
 * and its glyph images are cached, the critical path in renderString() will draw the glyphs as fast as if using a bitmap font.
 * Strings are cached using weak references through a two layer string cache. Strings that are no longer in use by Minecraft will
 * be evicted from the cache, while the pre-rendered images of individual glyphs remains cached forever. The following diagram
 * illustrates how this works:
 *
 * <pre>
 * String passed to            Key object considers      Entry object holds       Each Glyph object      GlyphCache.Entry stores
 * renderString();             all ASCII digits equal    an array of Glyph        belongs to only one    the texture ID, image
 * mapped by weak              to zero ('0');            objects which may        Entry object; it has   width/height and
 * weakRefCache                mapped by weak            not directly             the glyph x/y pos      normalized texture
 * to Key object               stringCahe to Entry       correspond to Unicode    within the string      coordinates.
 *                                                       chars in string
 * String("Fi1") ------------\                                               ---> Glyph("F") ----------> GlyphCache.Entry("F")
 *                    N:1     \                1:1                     1:N  /                    N:1
 * String("Fi4") ------------> Key("Fi0") -------------> Entry("Fi0") -----+----> Glyph("i") ----------> GlyphCache.Entry("i")
 *                                                                          \                    N:1
 *                                                                           ---> Glyph("0") -----\
 *                                                                                                 ----> GlyphCache.Entry("0")
 *                                                                           ---> Glyph("0") -----/
 *                    N:1                      1:1                     1:N  /                    N:1
 * String("Be1") ------------> Key("Be0") -------------> Entry("Be0") -----+----> Glyph("e") ----------> GlyphCache.Entry("e")
 *                                                                          \                    N:1
 *                                                                           ---> Glyph("B") ----------> GlyphCache.Entry("B")
 * </pre>
 */
public class StringCache
{
    /** Vertical adjustment (in pixels * 2) to string position because Minecraft uses top of string instead of baseline */
    private static final int BASELINE_OFFSET = 7;

    /** Offset from the string's baseline as which to draw the underline (in pixels) */
    private static final int UNDERLINE_OFFSET = 1;

    /** Thickness of the underline (in pixels) */
    private static final int UNDERLINE_THICKNESS = 2;

    /** Offset from the string's baseline as which to draw the strikethrough line (in pixels) */
    private static final int STRIKETHROUGH_OFFSET = -6;

    /** Thickness of the strikethrough line (in pixels) */
    private static final int STRIKETHROUGH_THICKNESS = 2;

    /** Reference to the unicode.FontRenderer class. Needed for creating GlyphVectors and retrieving glyph texture coordinates. */
    private GlyphCache glyphCache;

    /**
     * Color codes from original FontRender class. First 16 entries are the primary chat colors; second 16 are darker versions
     * used for drop shadows.
     */
    private int colorTable[];

    /**
     * A cache of recently seen strings to their fully layed-out state, complete with color changes and texture coordinates of
     * all pre-rendered glyph images needed to display this string. The weakRefCache holds strong references to the Key
     * objects used in this map.
     */
    private WeakHashMap<Key, Entry> stringCache = new WeakHashMap();

    /**
     * Every String passed to the public renderString() function is added to this WeakHashMap. As long as As long as Minecraft
     * continues to hold a strong reference to the String object (i.e. from TileEntitySign and ChatLine) passed here, the
     * weakRefCache map will continue to hold a strong reference to the Key object that said strings all map to (multiple strings
     * in weakRefCache can map to a single Key if those strings only differ by their ASCII digits).
     */
    private WeakHashMap<String, Key> weakRefCache = new WeakHashMap();

    /**
     * Temporary Key object re-used for lookups with stringCache.get(). Using a temporary object like this avoids the overhead
     * of allocating new objects in the critical rendering path. Of course, new Key objects are always created when adding
     * a mapping to stringCache.
     */
    private Key lookupKey = new Key();

    /**
     * Pre-cached glyphs for the ASCII digits 0-9 (in that order). Used by renderString() to substiture digit glyphs on the fly
     * as a performance boost. The speed up is most noticable on the F3 screen which rapidly displays lots of changing numbers.
     * The 4 element array is index by the font style (combination of Font.PLAIN, Font.BOLD, and Font.ITALIC), and each of the
     * nested elements is index by the digit value 0-9.
     */
    private Glyph[][] digitGlyphs = new Glyph[4][];

    /** True if digitGlyphs[] has been assigned and cacheString() can begin replacing all digits with '0' in the string */
    private boolean digitGlyphsReady = false;

    /**
     * Wraps a String and acts as the key into stringCache. The hashCode() and equals() methods consider all ASCII digits
     * to be equal when hashing and comparing Key objects together. Therefore, Strings which only differ in their digits will
     * be all hashed together into the same entry. The renderString() method will then substitute the correct digit glyph on
     * the fly. This special digit handling gives a significant speedup on the F3 debug screen.
     */
    static private class Key
    {
        /**
         * A copy of the String which this Key is indexing. A copy is used to avoid creating a strong reference to the original
         * passed into renderString(). When the original String is no longer needed by Minecraft, it will be garbage collected
         * and the WeakHashMaps in StringCache will allow this Key object and its associated Entry object to be garbage
         * collected as well.
         */
        public String str;

        /**
         * Computes a hash code on str in the same manner as the String class, except all ASCII digits hash as '0'
         * @return the augmented hash code on str
         */
        @Override
        public int hashCode()
        {
            int code = 0, length = str.length();

            for (int index = 0; index < length; index++)
            {
                char c = str.charAt(index);
                if(c >= '0' && c <= '9')
                {
                    c = '0';
                }
                code = (code * 31) + c;
            }

            return code;
        }

        /**
         * Compare str against another object (specifically, the object's string representation as returned by toString).
         * All ASCII digits are considered equal by this method, as long as they are at the same index within the string.
         * @return true if the strings are the identical, or only differ in their ASCII digits
         */
        @Override
        public boolean equals(Object o)
        {
            /* Calling toString on a String object simply returns itself so no new object allocation is performed */
            String other = o.toString();
            int length = str.length();

            if(length != other.length())
            {
                return false;
            }

            for(int index = 0; index < length; index++)
            {
                char c1 = str.charAt(index);
                char c2 = other.charAt(index);

                if(c1 != c2 && (c1 < '0' || c1 > '9' || c2 < '0' || c2 > '9'))
                {
                    return false;
                }
            }

            return true;
        }

        /**
         * Returns the contained String object within this Key.
         * @return the str object
         */
        @Override
        public String toString()
        {
            return str;
        }
    }

    /** This entry holds the layed out glyph positions for the cached string along with some relevant metadata. */
    static private class Entry
    {
        /** A weak reference back to the Key object in stringCache that maps to this Entry. */
        public WeakReference<Key> keyRef;

        /** The total horizontal advance (i.e. width) for this string in pixels. */
        public int advance;

        /** Array of fully layed out glyphs for the string. Sorted by logical order of characters (i.e. glyph.stringIndex) */
        public Glyph glyphs[];

        /** Array of color code locations from the original string */
        public ColorCode colors[];

        /** True if the string uses strikethrough or underlines anywhere and needs an extra pass in renderString() */
        public boolean specialRender;
    }

    /** Identifies the location and value of a single color code in the original string */
    static private class ColorCode implements Comparable<Integer>
    {
        /** Bit flag used with renderStyle to request the underline style */
        public static final byte UNDERLINE = 1;

        /** Bit flag used with renderStyle to request the strikethrough style */
        public static final byte STRIKETHROUGH = 2;

        /** The index into the original string (i.e. with color codes) for the location of this color code. */
        public int stringIndex;

        /** The index into the stripped string (i.e. with no color codes) of where this color code would have appeared */
        public int stripIndex;

        /** The numeric color code (i.e. index into the colorCode[] array); -1 to reset default color */
        public byte colorCode;

        /** Combination of Font.PLAIN, Font.BOLD, and Font.ITALIC specifying font specific syles */
        public byte fontStyle;

        /** Combination of UNDERLINE and STRIKETHROUGH flags specifying effects performed by renderString() */
        public byte renderStyle;

        /**
         * Performs numeric comparison on stripIndex. Allows binary search on ColorCode arrays in layoutStyle.
         * @param i the Integer object being compared
         * @return either -1, 0, or 1 if this < other, this == other, or this > other
         */
        @Override
        public int compareTo(Integer i)
        {
            return (stringIndex == i.intValue()) ? 0 : (stringIndex < i.intValue()) ? -1 : 1;
        }
    }

    /**
     * Identifies a single glyph in the layed-out string. Includes a reference to a GlyphCache.Entry with the OpenGL texture ID
     * and position of the pre-rendered glyph image, and includes the x/y pixel coordinates of where this glyph occurs within
     * the string to which this Glyph object belongs.
     */
    static private class Glyph implements Comparable<Glyph>
    {
        /** The index into the original string (i.e. with color codes) for the character that generated this glyph. */
        public int stringIndex;

        /** Texture ID and position/size of the glyph's pre-rendered image within the cache texture. */
        public GlyphCache.Entry texture;

        /** Glyph's horizontal position (in pixels) relative to the entire string's baseline */
        public int x;

        /** Glyph's vertical position (in pixels) relative to the entire string's baseline */
        public int y;

        /** Glyph's horizontal advance (in pixels) used for strikethrough and underline effects */
        public int advance;

        /**
         * Allows arrays of Glyph objects to be sorted. Performs numeric comparison on stringIndex.
         * @param o the other Glyph object being compared with this one
         * @return either -1, 0, or 1 if this < other, this == other, or this > other
         */
        @Override
        public int compareTo(Glyph o)
        {
            return (stringIndex == o.stringIndex) ? 0 : (stringIndex < o.stringIndex) ? -1 : 1;
        }
    }

    /**
     * A single StringCache object is allocated by Minecraft's FontRenderer which forwards all string drawing and requests for
     * string width to this class.
     *
     * @param renderEngine needed for allocating new OpenGL textures
     * @param colors 32 element array of RGBA colors corresponding to the 16 text color codes followed by 16 darker version of the
     * color codes for use as drop shadows
     */
    public StringCache(RenderEngine renderEngine, int colors[])
    {
        glyphCache = new GlyphCache(renderEngine);
        colorTable = colors;

        /* Pre-cache the ASCII digits to allow for fast glyph substitution; need to cache each font style combination */
        digitGlyphs[Font.PLAIN] = cacheString("0123456789").glyphs;
        digitGlyphs[Font.BOLD] = cacheString("�l0123456789").glyphs;
        digitGlyphs[Font.ITALIC] = cacheString("�o0123456789").glyphs;
        digitGlyphs[Font.BOLD | Font.ITALIC] = cacheString("�l�o0123456789").glyphs;
        digitGlyphsReady = true;
    }

    /**
     * Render a single-line string to the screen using the current OpenGL color. The (x,y) coordinates are of the uppet-left
     * corner of the string's bounding box, rather than the baseline position as is typical with fonts. This function will also
     * add the string to the cache so the next renderString() call with the same string is faster.
     *
     * @param str the string being rendered; it can contain color codes
     * @param x the x coordinate to draw at
     * @param y the y coordinate to draw at
     * @param color the initial RGBA color to use when drawing the string; embedded color codes can override the RGB component
     * @param shadowFlag if true, color codes are replaces by a darker version used for drop shadows
     * @return the total advance (horizontal distance) of this string
     *
     * @todo Add optional NumericShaper to replace ASCII digits with locale specific ones
     * @todo Test if using Tessellator fixes the white text problem with old broken OpenGL drivers (Possibly some Intel?)
     * @todo Add support for the "k" code which randomly replaces letters on each render (used only by splash screen)
     * @todo Pre-sort by texture to minimize binds; can store colors per glyph in string cache
     */
    public int renderString(String str, int startX, int startY, int initialColor, boolean shadowFlag)
    {
        /* Check for invalid arguments */
        if(str == null || str.isEmpty())
        {
            return 0;
        }

        /* Make sure the entire string is cached before rendering and return its glyph representation */
        Entry entry = cacheString(str);

        /* Adjust the baseline of the string because the startY coordinate in Minecraft is for the top of the string */
        startY += BASELINE_OFFSET;

        /* Color currently selected by color code; reapplied to Tessellator instance after glBindTexture() */
        int color = initialColor;

        /* Track which texture is currently bound to minimize the number of glBindTexture() and Tessellator.draw() calls needed */
        int boundTextureName = 0;

        /* Using the Tessellator to queue up data in a vertex array and then draw all at once should be faster than immediate mode */
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA(color >> 16 & 0xff, color >> 8 & 0xff, color & 0xff, color >> 24 & 0xff);

        /* The currently active font syle is needed to select the proper ASCII digit style for fast replacement */
        int fontStyle = Font.PLAIN;

        for(int glyphIndex = 0, colorIndex = 0; glyphIndex < entry.glyphs.length; glyphIndex++)
        {
            /*
             * If the original string had a color code at this glyph's position, then change the current GL color that gets added
             * to the vertex array. Note that only the RGB component of the color is replaced by a color code; the alpha component
             * of the original color passed into this function will remain. The while loop handles multiple consecutive color codes,
             * in which case only the last such color code takes effect.
             */
            while(colorIndex < entry.colors.length && entry.glyphs[glyphIndex].stringIndex >= entry.colors[colorIndex].stringIndex)
            {
                color = applyColorCode(entry.colors[colorIndex].colorCode, initialColor, shadowFlag);
                fontStyle = entry.colors[colorIndex].fontStyle;
                colorIndex++;
            }

            /* Select the current glyph's texture information and horizontal layout position within this string */
            Glyph glyph = entry.glyphs[glyphIndex];
            GlyphCache.Entry texture = glyph.texture;
            int glyphX = glyph.x;

            /*
             * Replace ASCII digits in the string with their respective glyphs; strings differing by digits are only cached once.
             * If the new replacement glyph has a different width than the original placeholder glyph (e.g. the '1' glyph is often
             * narrower than other digits), re-center the new glyph over the placeholder's position to minimize the visual impact
             * of the width mismatch.
             */
            char c = str.charAt(glyph.stringIndex);
            if(c >= '0' && c <= '9')
            {
                int oldWidth = texture.width;
                texture = digitGlyphs[fontStyle][c - '0'].texture;
                int newWidth = texture.width;
                glyphX += (oldWidth - newWidth) >> 1;
            }

            /*
             * Make sure the OpenGL texture storing this glyph's image is bound (if not already bound). All pending glyphs in the
             * Tessellator's vertex array must be drawn before switching textures, otherwise they would erroneously use the new
             * texture as well.
             */
            if(boundTextureName != texture.textureName)
            {
                tessellator.draw();
                tessellator.startDrawingQuads();
                tessellator.setColorRGBA(color >> 16 & 0xff, color >> 8 & 0xff, color & 0xff, color >> 24 & 0xff);

                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.textureName);
                boundTextureName = texture.textureName;
            }

            /* The divide by 2.0F is needed to align with the scaled GUI coordinate system; startX/startY are already scaled */
            float x1 = startX + (glyphX) / 2.0F;
            float x2 = startX + (glyphX + texture.width) / 2.0F;
            float y1 = startY + (glyph.y) / 2.0F;
            float y2 = startY + (glyph.y + texture.height) / 2.0F;

            tessellator.addVertexWithUV(x1, y1, 0, texture.u1, texture.v1);
            tessellator.addVertexWithUV(x1, y2, 0, texture.u1, texture.v2);
            tessellator.addVertexWithUV(x2, y2, 0, texture.u2, texture.v2);
            tessellator.addVertexWithUV(x2, y1, 0, texture.u2, texture.v1);
        }

        /* Draw any remaining glyphs in the Tessellator's vertex array (there should be at least one glyph pending) */
        tessellator.draw();

        /* Draw strikethrough and underlines if the string uses them anywhere */
        if(entry.specialRender)
        {
            int renderStyle = 0;

            /* Use initial color passed to renderString(); disable texturing to draw solid color lines */
            color = initialColor;
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            tessellator.startDrawingQuads();
            tessellator.setColorRGBA(color >> 16 & 0xff, color >> 8 & 0xff, color & 0xff, color >> 24 & 0xff);

            for(int glyphIndex = 0, colorIndex = 0; glyphIndex < entry.glyphs.length; glyphIndex++)
            {
                /*
                 * If the original string had a color code at this glyph's position, then change the current GL color that gets added
                 * to the vertex array. The while loop handles multiple consecutive color codes, in which case only the last such
                 * color code takes effect.
                 */
                while(colorIndex < entry.colors.length && entry.glyphs[glyphIndex].stringIndex >= entry.colors[colorIndex].stringIndex)
                {
                    color = applyColorCode(entry.colors[colorIndex].colorCode, initialColor, shadowFlag);
                    renderStyle = entry.colors[colorIndex].renderStyle;
                    colorIndex++;
                }

                /* Select the current glyph within this string for its layout position */
                Glyph glyph = entry.glyphs[glyphIndex];

                /* The strike/underlines are drawn beyond the glyph's width to include the extra space between glyphs */
                int glyphSpace = glyph.advance - glyph.texture.width;

                /* Draw underline under glyph if the style is enabled */
                if((renderStyle & ColorCode.UNDERLINE) != 0)
                {
                    /* The divide by 2.0F is needed to align with the scaled GUI coordinate system; startX/startY are already scaled */
                    float x1 = startX + (glyph.x - glyphSpace) / 2.0F;
                    float x2 = startX + (glyph.x + glyph.advance) / 2.0F;
                    float y1 = startY + (UNDERLINE_OFFSET) / 2.0F;
                    float y2 = startY + (UNDERLINE_OFFSET + UNDERLINE_THICKNESS) / 2.0F;

                    tessellator.addVertex(x1, y1, 0);
                    tessellator.addVertex(x1, y2, 0);
                    tessellator.addVertex(x2, y2, 0);
                    tessellator.addVertex(x2, y1, 0);
                }

                /* Draw strikethrough in the middle of glyph if the style is enabled */
                if((renderStyle & ColorCode.STRIKETHROUGH) != 0)
                {
                    /* The divide by 2.0F is needed to align with the scaled GUI coordinate system; startX/startY are already scaled */
                    float x1 = startX + (glyph.x - glyphSpace) / 2.0F;
                    float x2 = startX + (glyph.x + glyph.advance) / 2.0F;
                    float y1 = startY + (STRIKETHROUGH_OFFSET) / 2.0F;
                    float y2 = startY + (STRIKETHROUGH_OFFSET + STRIKETHROUGH_THICKNESS) / 2.0F;

                    tessellator.addVertex(x1, y1, 0);
                    tessellator.addVertex(x1, y2, 0);
                    tessellator.addVertex(x2, y2, 0);
                    tessellator.addVertex(x2, y1, 0);
                }
            }

            /* Finish drawing the last strikethrough/underline segments */
            tessellator.draw();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }

        /* Return total horizontal advance (slightly wider than the bounding box, but close enough for centering strings) */
        return entry.advance / 2;
    }

    /**
     * Return the width of a string in pixels. Used for centering strings inside GUI buttons.
     *
     * @param str compute the width of this string
     * @return the width in pixels (divided by 2; this matches the scaled coordinate system used by GUIs in Minecraft)
     */
    public int getStringWidth(String str)
    {
        /* Check for invalid arguments */
        if(str == null || str.isEmpty())
        {
            return 0;
        }

        /* Make sure the entire string is cached and rendered since it will probably be used again in a renderString() call */
        Entry entry = cacheString(str);

        /* Return total horizontal advance (slightly wider than the bounding box, but close enough for centering strings) */
        return entry.advance / 2;
    }

    /**
     * Return the number of characters in a string that will completly fit inside the specified width when rendered.
     *
     * @param str the String to analyze
     * @param width the desired string width (in GUI coordinate system)
     * @return the number of characters from str that will fit inside width
     */
    public int sizeStringToWidth(String str, int width)
    {
        /* Check for invalid arguments */
        if(str == null || str.isEmpty())
        {
            return 0;
        }

        /* Convert the width from GUI coordinate system to pixels */
        width += width;

        /* The glyph array for a string is sorted by the string's logical character position */
        Glyph glyphs[] = cacheString(str).glyphs;

        /* Add up the individual advance of each glyph until it exceeds the specified width */
        int advance = 0, index = 0;
        while(index < glyphs.length && advance <= width)
        {
            advance += glyphs[index].advance;
            index++;
        }

        /* The string index of the last glyph that wouldn't fit gives the total desired length of the string in characters */
        return index < glyphs.length ? glyphs[index].stringIndex : str.length();
    }

    /**
     * Trim a string so that it fits in the specified width when rendered, optionally reversing the string
     *
     * @param str the String to trim
     * @param width the desired string width (in GUI coordinate system)
     * @param reverse if true, the returned string will also be reversed
     * @return the trimmed and optionally reversed string
     */
    public String trimStringToWidth(String str, int width, boolean reverse)
    {
        int length = sizeStringToWidth(str, width);
        str = str.substring(0, length);

        if(reverse)
        {
            str = (new StringBuilder(str)).reverse().toString();
        }

        return str;
    }

    /**
     * Apply a new vertex color to the Tessellator instance based on the numeric chat color code. Only the RGB component of the
     * color is replaced by a color code; the alpha component of the original default color will remain. 
     *
     * @param colorCode the chat color code as a number 0-15 or -1 to reset the default color
     * @param color the default color used when the colorCode is -1
     * @param shadowFlag ir true, the color code will select a darker version of the color suitable for drop shadows
     * @return the new RGBA color set by this function
     */
    private int applyColorCode(int colorCode, int color, boolean shadowFlag)
    {
        /* A -1 color code indicates a reset to the initial color passed into renderString() */
        if(colorCode != -1)
        {
            colorCode = shadowFlag ? colorCode + 16 : colorCode;
            color = colorTable[colorCode] & 0xffffff | color & 0xff000000;
        }

        Tessellator.instance.setColorRGBA(color >> 16 & 0xff, color >> 8 & 0xff, color & 0xff, color >> 24 & 0xff);
        return color;
    }

    /**
     * Add a string to the string cache by perform full layout on it, remembering its glyph positions, and making sure that
     * every font glyph used by the string is pre-rendering. If this string has already been cached, then simply return its
     * existing Entry from the cahe. Note that for caching purposes, this method considers two strings to be identical if they
     * only differ in their ASCII digits; the renderString() method performs fast glyph substitution based on the actual digits
     * in the string at the time.
     *
     * @param str this String will be layed out and added to the cache (or looked up, if alraedy cached)
     * @return the string's cache entry containing all the glyph positions
     */
    private Entry cacheString(String str)
    {
        /* Re-use existing lookupKey to avoid allocation overhead on the critical rendering path */
        lookupKey.str = str;

        /*
         * New Key object allocated only if the string was not found in the StringCache using lookupKey. This variable must
         * be outside the (entry == null) code block to have a temporary strong reference between the time when the Key is
         * added to stringCache and added to weakRefCache.
         */
        Key key;

        /* If this string is already in the cache, simply return the cached Entry object */
        Entry entry = stringCache.get(lookupKey);
        if(entry == null)
        {
            /* layoutGlyphVector() requires a char[] so create it here and pass it around to avoid duplication later on */
            char text[] = str.toCharArray();

            /* Strip all color codes from the string */
            entry = new Entry();
            int length = stripColorCodes(entry, str, text);

            /* Layout the entire string, splitting it up by color codes and the Unicode bidirectional algorithm */
            List<Glyph> glyphList = new ArrayList();
            entry.advance = (int) layoutBidiString(glyphList, text, 0, length, entry.colors);

            /* Convert the accumulated Glyph list to an array for efficient storage */
            entry.glyphs = new Glyph[glyphList.size()];
            entry.glyphs = glyphList.toArray(entry.glyphs);

            /*
             * Sort Glyph array by stringIndex so it can be compared during rendering to the already sorted ColorCode array.
             * This will apply color codes in the string's logical character order and not the visual order on screen.
             */
            Arrays.sort(entry.glyphs);

            /* Do some post-processing on each Glyph object */
            int colorIndex = 0, shift = 0;
            for(int glyphIndex = 0; glyphIndex < entry.glyphs.length; glyphIndex++)
            {
                Glyph glyph = entry.glyphs[glyphIndex];

                /*
                 * Adjust the string index for each glyph to point into the original string with unstripped color codes. The while
                 * loop is necessary to handle multiple consecutive color codes with no visible glyphs between them. These new adjusted
                 * stringIndex can now be compared against the color stringIndex during rendering. It also allows lookups of ASCII
                 * digits in the original string for fast glyph replacement during rendering.
                 */
                while(colorIndex < entry.colors.length && glyph.stringIndex + shift >= entry.colors[colorIndex].stringIndex)
                {
                    shift += 2;
                    colorIndex++;
                }
                glyph.stringIndex += shift;
            }

            /* Wrap the string in a Key object (to change how ASCII digits are compared) and cache it along with the newly generated Entry */
            key = new Key();

            /* Make a copy of the original String to avoid creating a strong reference to it */
            key.str = new String(str);
            entry.keyRef = new WeakReference(key);
            stringCache.put(key, entry);
        }

        /*
         * Add the String passed into this method to the stringWeakMap so it keeps the Key reference live as long as the String is in use.
         * If an existing Entry was already found in the stringCache, it's possible that its Key has already been garbage collected. The
         * code below checks for this to avoid adding (str, null) entries into weakRefCache. Note that if a new Key object was created, it
         * will still be live because of the strong reference created by the "key" variable.
         */
        Key oldKey = entry.keyRef.get();
        if(oldKey != null)
        {
            weakRefCache.put(str, oldKey);
        }
        lookupKey.str = null;

        /* Return either the existing or the newly created entry so it can be accessed immediately */
        return entry;
    }

    /**
     * Remove all color codes from the string by shifting data in the text[] array over so it overwrites them. The value of each
     * color code and its position (relative to the new stripped text[]) is also recorded in a separate array. The color codes must
     * be removed for a font's context sensitive glyph substitution to work (like Arabic letter middle form).
     *
     * @param colorList each color change in the string will add a new ColorCode object to this list
     * @param str the string from which color codes will be stripped
     * @param text on input it should be an identical copy of str; on output it will be string with all color codes removed
     * @return the length of the new stripped string in text[]; actual text.length will not change because the array is not reallocated
     */
    private int stripColorCodes(Entry cacheEntry, String str, char text[])
    {
        List<ColorCode> colorList = new ArrayList();
        int start = 0, shift = 0, next;

        byte fontStyle = Font.PLAIN;
        byte renderStyle = 0;
        byte colorCode = -1;

        /* Search for section mark characters indicating the start of a color code (but only if followed by at least one character) */
        while((next = str.indexOf('\u00A7', start)) != -1 && next + 1 < str.length())
        {
            /*
             * Remove the two char color code from text[] by shifting the remaining data in the array over on top of it.
             * The "start" and "next" variables all contain offsets into the original unmodified "str" string. The "shift"
             * variable keeps track of how many characters have been sripped so far, and it's used to compute offsets into
             * the text[] array based on the start/next offsets in the original string.
             */
            System.arraycopy(text, next - shift + 2, text, next - shift, text.length - next - 2);

            /* Decode escape code used in the string and change current font style / color based on it */
            int code = "0123456789abcdefklmnor".indexOf(Character.toLowerCase(str.charAt(next + 1)));
            switch(code)
            {
                /* Random style; TODO: NOT IMPLEMENTED YET */
                case 16:
                    break;

                /* Bold style */
                case 17:
                    fontStyle |= Font.BOLD;
                    break;

                /* Strikethrough style */
                case 18:
                    renderStyle |= ColorCode.STRIKETHROUGH;
                    cacheEntry.specialRender = true;
                    break;

                /* Underline style */
                case 19:
                    renderStyle |= ColorCode.UNDERLINE;
                    cacheEntry.specialRender = true;
                    break;

                /* Italic style */
                case 20:
                    fontStyle |= Font.ITALIC;
                    break;

                /* Plain style */
                case 21:
                    fontStyle = Font.PLAIN;
                    renderStyle = 0;
                    colorCode = -1; // This may be a bug in Minecraft's original FontRenderer
                    break;

                /* Otherwise, must be a color code or some other unsupported code */
                default:
                    if(code >= 0 && code <= 15)
                    {
                        colorCode = (byte) code;
                        fontStyle = Font.PLAIN; // This may be a bug in Minecraft's original FontRenderer
                        renderStyle = 0;        // This may be a bug in Minecraft's original FontRenderer
                    }
                    break;
            }

            /* Create a new ColorCode object that tracks the position of the code in the original string */
            ColorCode entry = new ColorCode();
            entry.stringIndex = next;
            entry.stripIndex = next - shift;
            entry.colorCode = colorCode;
            entry.fontStyle = fontStyle;
            entry.renderStyle = renderStyle;
            colorList.add(entry);

            /* Resume search for section marks after skipping this one */
            start = next + 2;
            shift += 2;
        }

        /* Convert the accumulated ColorCode list to an array for efficient storage */
        cacheEntry.colors = new ColorCode[colorList.size()];
        cacheEntry.colors = colorList.toArray(cacheEntry.colors);

        /* Return the new length of the string after all color codes were removed */
        return text.length - shift;
    }

    /**
     * Split a string into contiguous LTR or RTL sections by applying the Unicode Bidirectional Algorithm. Calls layoutString()
     * for each contiguous run to perform further analysis.
     *
     * @param glyphList will hold all new Glyph objects allocated by layoutFont()
     * @param text the string to lay out
     * @param start the offset into text at which to start the layout
     * @param limit the (offset + length) at which to stop performing the layout
     * @return the total advance (horizontal distance) of this string
     */
    private int layoutBidiString(List<Glyph> glyphList, char text[], int start, int limit, ColorCode colors[])
    {
        int advance = 0;

        /* Avoid performing full bidirectional analysis if text has no "strong" right-to-left characters */
        if(Bidi.requiresBidi(text, start, limit))
        {
            /* Note that while requiresBidi() uses start/limit the Bidi constructor uses start/length */
            Bidi bidi = new Bidi(text, start, null, 0, limit - start, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);

            /* If text is entirely right-to-left, then insert an EntryText node for the entire string */
            if(bidi.isRightToLeft())
            {
                return layoutStyle(glyphList, text, start, limit, Font.LAYOUT_RIGHT_TO_LEFT, advance, colors);
            }

            /* Otherwise text has a mixture of LTR and RLT, and it requires full bidirectional analysis */
            else
            {
                int runCount = bidi.getRunCount();
                byte levels[] = new byte[runCount];
                Integer ranges[] = new Integer[runCount];

                /* Reorder contiguous runs of text into their display order from left to right */
                for(int index = 0; index < runCount; index++)
                {
                    levels[index] = (byte) bidi.getRunLevel(index);
                    ranges[index] = new Integer(index);
                }
                Bidi.reorderVisually(levels, 0, ranges, 0, runCount);

                /*
                 * Every GlyphVector must be created on a contiguous run of left-to-right or right-to-left text. Keep track of
                 * the horizontal advance between each run of text, so that the glyphs in each run can be assigned a position relative
                 * to the start of the entire string and not just relative to that run.
                 */
                for(int visualIndex = 0; visualIndex < runCount; visualIndex++)
                {
                    int logicalIndex = ranges[visualIndex];

                    /* An odd numbered level indicates right-to-left ordering */
                    int layoutFlag = (bidi.getRunLevel(logicalIndex) & 1) == 1 ? Font.LAYOUT_RIGHT_TO_LEFT : Font.LAYOUT_LEFT_TO_RIGHT;
                    advance = layoutStyle(glyphList, text, start + bidi.getRunStart(logicalIndex), start + bidi.getRunLimit(logicalIndex),
                        layoutFlag, advance, colors);
                }
            }

            return advance;
        }

        /* If text is entirely left-to-right, then insert an EntryText node for the entire string */
        else
        {
            return layoutStyle(glyphList, text, start, limit, Font.LAYOUT_LEFT_TO_RIGHT, advance, colors);
        }
    }

    private int layoutStyle(List<Glyph> glyphList, char text[], int start, int limit, int layoutFlags, int advance, ColorCode colors[])
    {
        int currentFontStyle = Font.PLAIN;

        /* Find ColorCode object with stripIndex <= start; that will have the font style in effect at the beginning of this text run */
        int colorIndex = Arrays.binarySearch(colors, start);

        /*
         * If no exact match is found, Arrays.binarySearch() returns (-(insertion point) - 1) where the insertion point is the index
         * of the first ColorCode with a stripIndex > start. In that case, colorIndex is adjusted to select the immediately preceding
         * ColorCode whose stripIndex < start.
         */
        if(colorIndex < 0)
        {
            colorIndex = -colorIndex - 2;
        }

        /* Break up the string into segments, where each segment has the same font style in use */
        while(start < limit)
        {
            int next = limit;

            /* In case of multiple consecutive color codes with the same stripIndex, select the last one which will have active font style */
            while(colorIndex >= 0 && colorIndex < (colors.length - 1) && colors[colorIndex].stripIndex == colors[colorIndex + 1].stripIndex)
            {
                colorIndex++;
            }

            /* If an actual ColorCode object was found (colorIndex within the array), use its fontStyle for layout and render */
            if(colorIndex >= 0 && colorIndex < colors.length)
            {
                currentFontStyle = colors[colorIndex].fontStyle;
            }

            /*
             * Search for the next ColorCode that uses a different fontStyle than the current one. If found, the stripIndex of that
             * new code is the split point where the string must be split into a separately styled segment.
             */
            while(++colorIndex < colors.length)
            {
                if(colors[colorIndex].fontStyle != currentFontStyle)
                {
                    next = colors[colorIndex].stripIndex;
                    break;
                }
            }

            /* Layout the string segment with the style currently selected by the last color code */
            advance = layoutString(glyphList, text, start, next, layoutFlags, advance, currentFontStyle);
            start = next;
        }

        return advance;
    }

    /**
     * Given a string that runs contiguously LTR or RTL, break it up into individual segments based on which fonts can render
     * which characters in the string. Calls layoutFont() for each portion of the string that can be layed out with a single
     * font.
     *
     * @param glyphList will hold all new Glyph objects allocated by layoutFont()
     * @param text the string to lay out
     * @param start the offset into text at which to start the layout
     * @param limit the (offset + length) at which to stop performing the layout
     * @param layoutFlags either Font.LAYOUT_RIGHT_TO_LEFT or Font.LAYOUT_LEFT_TO_RIGHT
     * @param advance the horizontal advance (i.e. X position) returned by previous call to layoutString()
     * @param style combination of Font.PLAIN, Font.BOLD, and Font.ITALIC to select a fonts with some specific style
     * @return the advance (horizontal distance) of this string plus the advance passed in as an argument
     *
     * @todo Correctly handling RTL font selection requires scanning the sctring from RTL as well.
     * @todo Use bitmap fonts as a fallback if no OpenType font could be found
     */
    private int layoutString(List<Glyph> glyphList, char text[], int start, int limit, int layoutFlags, int advance, int style)
    {
        /*
         * Convert all digits in the string to a '0' before layout to ensure that any glyphs replaced on the fly will all have
         * the same positions. Under Windows, Java's "SansSerif" logical font uses the "Arial" font for digits, in which the "1"
         * digit is slightly narrower than all other digits. Checking the digitGlyphsReady flag prevents a chicken-and-egg
         * problem where the digit glyphs have to be initially cached and the digitGlyphs[] array initialized without replacing
         * every digit with '0'.
         */
        if(digitGlyphsReady)
        {
            for(int index = start; index < limit; index++)
            {
                if(text[index] >= '0' && text[index] <= '9')
                {
                    text[index] = '0';
                }
            }
        }

        /* Break the string up into segments, where each segment can be displayed using a single font */
        while(start < limit)
        {
            Font font = glyphCache.lookupFont(text, start, limit, style);
            int next = font.canDisplayUpTo(text, start, limit);

            /* canDisplayUpTo returns -1 if the entire string range is supported by this font */
            if(next == -1)
            {
                next = limit;
            }

            /*
             * canDisplayUpTo() returns start if the starting character is not supported at all. In that case, draw just the
             * one unsupported character (which will use the font's "missing glyph code"), then retry the lookup again at the
             * next character after that.
             */
            if(next == start)
            {
                next++;
            }

            advance = layoutFont(glyphList, text, start, next, layoutFlags, advance, font);
            start = next;
        }

        return advance;
    }

    /**
     * Allocate new Glyph objects and add them to the glyph list. This sequence of Glyphs represents a portion of the
     * string where all glyphs run contiguously in either LTR or RTL and come from the same physical/logical font.
     *
     * @param glyphList all newly created Glyph objects are added to this list
     * @param text the string to layout
     * @param start the offset into text at which to start the layout
     * @param limit the (offset + length) at which to stop performing the layout
     * @param layoutFlags either Font.LAYOUT_RIGHT_TO_LEFT or Font.LAYOUT_LEFT_TO_RIGHT
     * @param advance the horizontal advance (i.e. X position) returned by previous call to layoutString()
     * @param font the Font used to layout a GlyphVector for the string
     * @return the advance (horizontal distance) of this string plus the advance passed in as an argument
     *
     * @todo need to ajust position of all glyphs if digits are present, by assuming every digit should be 0 in length
     */
    private int layoutFont(List<Glyph> glyphList, char text[], int start, int limit, int layoutFlags, int advance, Font font)
    {
        /* Ensure that all glyphs used by the string are pre-rendered and cached in the texture */
        glyphCache.cacheGlyphs(font, text, start, limit, layoutFlags);

        /* Creating a GlyphVector takes care of all language specific OpenType glyph substitutions and positionings */
        GlyphVector vector = glyphCache.layoutGlyphVector(font, text, start, limit, layoutFlags);

        /*
         * Extract all needed information for each glyph from the GlyphVector so it won't be needed for actual rendering.
         * Note that initially, glyph.start holds the character index into the stripped text array. But after the entire
         * string is layed out, this field will be adjusted on every Glyph object to correctly index the original unstripped
         * string.
         */
        Glyph glyph = null;
        int numGlyphs = vector.getNumGlyphs();
        for(int index = 0; index < numGlyphs; index++)
        {
            Point position = vector.getGlyphPixelBounds(index, null, advance, 0).getLocation();

            /* Compute horizontal advance for the previous glyph based on this glyph's position */
            if(glyph != null)
            {
                glyph.advance = position.x - glyph.x;
            }

            /*
             * Allocate a new glyph object and add to the glyphList. The glyph.stringIndex here is really like stripIndex but
             * it will be corrected later to account for the color codes that have been stripped out.
             */
            glyph = new Glyph();
            glyph.stringIndex = start + vector.getGlyphCharIndex(index);
            glyph.texture = glyphCache.lookupGlyph(font, vector.getGlyphCode(index));
            glyph.x = position.x;
            glyph.y = position.y;
            glyphList.add(glyph);
        }

        /* Compute the advance position of the last glyph (or only glyph) since it can't be done by the above loop */
        advance += (int) vector.getGlyphPosition(numGlyphs).getX();
        if(glyph != null)
        {
            glyph.advance = advance - glyph.x;
        }

        /* Return the overall horizontal advance in pixels from the start of string */
        return advance;
    }
}