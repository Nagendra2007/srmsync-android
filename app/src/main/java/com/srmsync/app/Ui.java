package com.srmsync.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;

/** Colours and fonts for the native screens, in light and dark versions. */
final class Ui {

    // Brand colours, taken from the app icon.
    static final int RED = Color.parseColor("#E3262B");
    static final int RED_SOFT = Color.parseColor("#33E3262B");

    final boolean dark;
    final int hero;        // the navy status board
    final int heroText;
    final int heroMuted;
    final int heroTrack;   // empty part of the day strip
    final int ringerOn;    // calm blue for "phone rings normally"
    final int bg;
    final int card;
    final int line;
    final int text;
    final int muted;

    final Typeface regular;
    final Typeface medium;
    final Typeface semibold;
    final Typeface boardSemi;   // condensed, for times
    final Typeface boardBold;

    private static Ui cached;
    private static boolean cachedDark;

    static Ui get(Context c) {
        boolean night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        if (cached == null || cachedDark != night) {
            cached = new Ui(c.getApplicationContext(), night);
            cachedDark = night;
        }
        return cached;
    }

    private Ui(Context c, boolean dark) {
        this.dark = dark;
        hero = Color.parseColor(dark ? "#14213A" : "#0F1B2D");
        heroText = Color.parseColor("#F4F7FC");
        heroMuted = Color.parseColor("#9FB0CB");
        heroTrack = Color.parseColor("#26FFFFFF");
        ringerOn = Color.parseColor("#7FB2FF");
        bg = Color.parseColor(dark ? "#0A1120" : "#F2F4F8");
        card = Color.parseColor(dark ? "#121B2D" : "#FFFFFF");
        line = Color.parseColor(dark ? "#223049" : "#E1E6EE");
        text = Color.parseColor(dark ? "#E8EDF6" : "#13203A");
        muted = Color.parseColor(dark ? "#8F9DB5" : "#5E6B82");

        regular = font(c, "Barlow-Regular.ttf", Typeface.NORMAL);
        medium = font(c, "Barlow-Medium.ttf", Typeface.NORMAL);
        semibold = font(c, "Barlow-SemiBold.ttf", Typeface.BOLD);
        boardSemi = font(c, "BarlowCondensed-SemiBold.ttf", Typeface.BOLD);
        boardBold = font(c, "BarlowCondensed-Bold.ttf", Typeface.BOLD);
    }

    private static Typeface font(Context c, String file, int fallbackStyle) {
        try {
            return Typeface.createFromAsset(c.getAssets(), "fonts/" + file);
        } catch (RuntimeException e) {
            return Typeface.create(Typeface.SANS_SERIF, fallbackStyle);
        }
    }
}
