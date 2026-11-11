package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import android.util.Log;

/**
 * Apple tvOS style "Home Settings" screen:
 * - Translucent Tahoe 27.2 frosted glass background with user-controlled opacity (30%, 50%, 70%, 100%)
 * - Left sidebar with category icons (Glyphs) and smooth pill highlight
 * - Bottom author signature "Created by Hafizov"
 * - Right pane with section headers and rounded frosted glass cards
 * - Real-time preference updates with zero reboot needed
 * - Smooth scrollable choice dialogs with focus preservation (never jumps back to Appearance tab)
 */
public final class LauncherSettingsActivity extends Activity {

    public static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";

    private static final String PREF_SETTINGS = Config.SETTINGS_NAME;
    private static final String PREF_WEATHER = "khujand_weather";
    private static final String PREF_PRAYER = "hanafi_prayer_prefs";
    private static final String PREF_WATCH = "watch_next_prefs";

    // Categories
    private static final int SEC_APPEARANCE = 0;
    private static final int SEC_WEATHER = 1;
    private static final int SEC_PRAYER = 2;
    private static final int SEC_SHELF = 3;
    private static final int SEC_DOCK = 4;
    private static final int SEC_BUTTONS = 5;
    private static final int SEC_SYSTEM = 6;
    private static final int SEC_ABOUT = 7;

    private static final String[][] CATEGORIES = {
            {"Внешний вид", "scene"},
            {"Погода и город", "cloud"},
            {"Времена намаза", "moon"},
            {"Рекомендации кино", "shelf"},
            {"Док и приложения", "apps"},
            {"Быстрые кнопки", "sliders"},
            {"Система и пульт", "remote"},
            {"О лаунчере", "info"}
    };

    private int activeSection = SEC_APPEARANCE;
    private float density;

    private View glassScrimView;
    private GradientDrawable glassScrimDrawable;

    private LinearLayout sideCol;
    private TextView rightHeadView;
    private ScrollView rightScroller;
    private LinearLayout rightList;
    private final List<View> sideRows = new ArrayList<>();
    private final List<View> contentCards = new ArrayList<>();
    private View lastFocusedCard = null;
    private boolean isDialogOpen = false;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        buildUi();
        selectSection(SEC_APPEARANCE, false);
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private void updateGlassScrim() {
        SharedPreferences sp = getSharedPreferences(PREF_SETTINGS, MODE_PRIVATE);
        int glassOpacity = sp.getInt("settings_glass_opacity", 50);
        int alphaTop = Math.round(glassOpacity * 255f / 100f * 0.90f);
        int alphaBottom = Math.round(glassOpacity * 255f / 100f);

        int colorTop = Color.argb(alphaTop, 0x14, 0x1E, 0x2C);
        int colorBottom = Color.argb(alphaBottom, 0x0A, 0x0E, 0x16);

        if (glassScrimDrawable == null) {
            glassScrimDrawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{colorTop, colorBottom});
        } else {
            glassScrimDrawable.setColors(new int[]{colorTop, colorBottom});
        }
        if (glassScrimView != null) {
            glassScrimView.setBackground(glassScrimDrawable);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);

        // Tahoe 27.2 frosted glass background scrim
        glassScrimView = new View(this);
        updateGlassScrim();
        root.addView(glassScrimView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Two columns layout
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(dp(44), dp(22), dp(48), dp(18));
        root.addView(columns, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Left Sidebar (270dp)
        sideCol = new LinearLayout(this);
        sideCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.MATCH_PARENT);
        columns.addView(sideCol, sideLp);

        TextView sideTitle = new TextView(this);
        sideTitle.setText("НАСТРОЙКИ");
        sideTitle.setTextColor(Color.WHITE);
        sideTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        sideTitle.setLetterSpacing(0.04f);
        sideTitle.setPadding(dp(12), dp(6), dp(12), dp(14));
        sideCol.addView(sideTitle);

        buildSidebar();

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT);
        divLp.leftMargin = dp(24);
        divLp.rightMargin = dp(28);
        divLp.topMargin = dp(14);
        divLp.bottomMargin = dp(14);
        columns.addView(divider, divLp);

        // Right Main Column
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        columns.addView(rightCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        rightHeadView = new TextView(this);
        rightHeadView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        rightHeadView.setTextColor(Color.WHITE);
        rightHeadView.setPadding(dp(12), dp(4), dp(12), dp(8));
        rightCol.addView(rightHeadView);

        rightScroller = new ScrollView(this);
        rightScroller.setVerticalScrollBarEnabled(false);
        rightScroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rightCol.addView(rightScroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rightList = new LinearLayout(this);
        rightList.setOrientation(LinearLayout.VERTICAL);
        rightList.setPadding(dp(12), dp(2), dp(12), dp(14));
        rightScroller.addView(rightList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Bottom hint bar
        TextView hint = new TextView(this);
        hint.setText("▲ / ▼ Навигация  ·  OK Выбрать / Изменить  ·  ◄ В разделы  ·  Назад Выход");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setSingleLine(true);
        GradientDrawable hintBg = new GradientDrawable();
        hintBg.setColor(0x80181F2A);
        hintBg.setCornerRadius(dp(12));
        hintBg.setStroke(dp(1), 0x22FFFFFF);
        hint.setBackground(hintBg);
        hint.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(4);
        hLp.leftMargin = dp(12);
        rightCol.addView(hint, hLp);

        setContentView(root);
    }

    private void buildSidebar() {
        sideCol.removeViews(1, sideCol.getChildCount() - 1);
        sideRows.clear();

        for (int i = 0; i < CATEGORIES.length; i++) {
            final int secIdx = i;
            final String title = CATEGORIES[i][0];
            final String glyph = CATEGORIES[i][1];

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            row.setClickable(true);
            row.setPadding(dp(10), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
            rLp.bottomMargin = dp(3);
            row.setLayoutParams(rLp);

            // Icon squircle
            FrameLayout iconBox = new FrameLayout(this);
            GradientDrawable ibBg = new GradientDrawable();
            ibBg.setCornerRadius(dp(8));
            ibBg.setColor(0x1FFFFFFF);
            iconBox.setBackground(ibBg);
            int boxSz = dp(28);

            ImageView iconView = new ImageView(this);
            iconView.setImageBitmap(Glyphs.get(glyph, dp(16), Color.WHITE));
            iconBox.addView(iconView, new FrameLayout.LayoutParams(
                    dp(16), dp(16), Gravity.CENTER));

            row.addView(iconBox, new LinearLayout.LayoutParams(boxSz, boxSz));

            TextView label = new TextView(this);
            label.setText(title);
            label.setTextColor(0xD0FFFFFF);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tLp.leftMargin = dp(12);
            row.addView(label, tLp);

            row.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    if (isDialogOpen) {
                        return;
                    }
                    if (lastFocusedCard != null) {
                        final View restore = lastFocusedCard;
                        v.post(() -> restore.requestFocus());
                        return;
                    }
                    if (activeSection != secIdx) {
                        selectSection(secIdx, false);
                        v.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                    }
                }
                updateSidebarState();
            });

            row.setOnClickListener(v -> {
                selectSection(secIdx, true);
                if (!contentCards.isEmpty()) {
                    lastFocusedCard = contentCards.get(0);
                    contentCards.get(0).requestFocus();
                }
            });

            row.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (!contentCards.isEmpty()) {
                        lastFocusedCard = contentCards.get(0);
                        contentCards.get(0).requestFocus();
                        return true;
                    }
                }
                return false;
            });

            sideCol.addView(row);
            sideRows.add(row);
        }

        // Spacer to push author signature to bottom
        View spacer = new View(this);
        sideCol.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Subtle Author Signature
        TextView authorLabel = new TextView(this);
        authorLabel.setText("Created by Hafizov");
        authorLabel.setTextColor(0x66FFFFFF);
        authorLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        authorLabel.setLetterSpacing(0.08f);
        authorLabel.setPadding(dp(12), dp(4), dp(12), dp(4));
        sideCol.addView(authorLabel);

        updateSidebarState();
    }

    private void updateSidebarState() {
        for (int i = 0; i < sideRows.size(); i++) {
            View row = sideRows.get(i);
            boolean isSelected = (i == activeSection);
            boolean isFocused = row.isFocused();

            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(12));

            FrameLayout iconBox = (FrameLayout) ((ViewGroup) row).getChildAt(0);
            ImageView iconView = (ImageView) iconBox.getChildAt(0);
            TextView label = (TextView) ((ViewGroup) row).getChildAt(1);

            GradientDrawable iconBg = new GradientDrawable();
            iconBg.setCornerRadius(dp(8));

            if (isFocused) {
                pill.setColor(Color.WHITE);
                iconBg.setColor(0x20000000);
                iconView.setImageBitmap(Glyphs.get(CATEGORIES[i][1], dp(16), Color.BLACK));
                label.setTextColor(Color.BLACK);
                row.setScaleX(1.02f);
                row.setScaleY(1.02f);
            } else if (isSelected) {
                pill.setColor(0x33FFFFFF);
                iconBg.setColor(0x28FFFFFF);
                iconView.setImageBitmap(Glyphs.get(CATEGORIES[i][1], dp(16), Color.WHITE));
                label.setTextColor(Color.WHITE);
                row.setScaleX(1.0f);
                row.setScaleY(1.0f);
            } else {
                pill.setColor(Color.TRANSPARENT);
                iconBg.setColor(0x18FFFFFF);
                iconView.setImageBitmap(Glyphs.get(CATEGORIES[i][1], dp(16), Color.WHITE));
                label.setTextColor(0xB0FFFFFF);
                row.setScaleX(1.0f);
                row.setScaleY(1.0f);
            }
            row.setBackground(pill);
            iconBox.setBackground(iconBg);
        }
    }

    private void selectSection(int secIdx, boolean focusFirstCard) {
        activeSection = secIdx;
        lastFocusedCard = null;
        rightHeadView.setText(CATEGORIES[secIdx][0]);
        // Do not duplicate "Внешний вид" header on the right pane (saves vertical space)
        rightHeadView.setVisibility(secIdx == SEC_APPEARANCE ? View.GONE : View.VISIBLE);
        updateSidebarState();
        buildSectionContent(secIdx);
        if (focusFirstCard && !contentCards.isEmpty()) {
            lastFocusedCard = contentCards.get(0);
            contentCards.get(0).requestFocus();
        }
    }

    private void buildSectionContent(int sec) {
        rightList.removeAllViews();
        contentCards.clear();
        rightScroller.scrollTo(0, 0);

        switch (sec) {
            case SEC_APPEARANCE:
                buildAppearanceSection();
                break;
            case SEC_WEATHER:
                buildWeatherSection();
                break;
            case SEC_PRAYER:
                buildPrayerSection();
                break;
            case SEC_SHELF:
                buildShelfSection();
                break;
            case SEC_DOCK:
                buildDockSection();
                break;
            case SEC_BUTTONS:
                buildButtonsSection();
                break;
            case SEC_SYSTEM:
                buildSystemSection();
                break;
            case SEC_ABOUT:
                buildAboutSection();
                break;
        }
    }

    private String getWallpaperDisplayName(String key, int rotMin) {
        if ("random".equalsIgnoreCase(key)) {
            return "🎲 Случайные обои (" + (rotMin > 0 ? rotMin + " мин" : "при запуске") + ")";
        }
        if ("none".equals(key)) return "Тёмный минимализм (чистое стекло)";
        File dir = Config.dir(this);
        File wpDir = dir == null ? null : new File(dir, "wallpapers");
        Map<String, String> captions = WallpaperCaptions.read(wpDir);
        if (captions.containsKey(key)) {
            return captions.get(key);
        }
        return key.replaceFirst("[.][^.]+$", "");
    }

    // --------------------------------------------------------------------------
    // 1. ВНЕШНИЙ ВИД
    // --------------------------------------------------------------------------
    private void buildAppearanceSection() {
        addHeader("ОБОИ И ТЕМЫ");

        SharedPreferences sp = getSharedPreferences(PREF_SETTINGS, MODE_PRIVATE);
        String wpChoice = sp.getString(Config.WALLPAPER_CHOICE_KEY, "random");
        int rotMinutes = sp.getInt("wallpaper_rotation_minutes", 15);

        final View[] rotCardRef = new View[1];

        File dir = Config.dir(this);
        File wpDir = dir == null ? null : new File(dir, "wallpapers");
        List<File> images = WallpaperRotation.images(wpDir);
        Map<String, String> captions = WallpaperCaptions.read(wpDir);

        List<String> labelsList = new ArrayList<>();
        List<String> valuesList = new ArrayList<>();

        labelsList.add("🎲 Случайные обои (слайдшоу)");
        valuesList.add("random");

        int num = 1;
        for (File img : images) {
            String fname = img.getName();
            String caption = captions.get(fname);
            String title = caption != null && !caption.isEmpty() ? caption : fname.replaceFirst("[.][^.]+$", "");
            String numPrefix = (num < 10 ? "0" : "") + num + ". ";
            labelsList.add(numPrefix + title);
            valuesList.add(fname);
            num++;
        }

        labelsList.add("Тёмный минимализм (чистое стекло)");
        valuesList.add("none");

        final String[] wpLabels = labelsList.toArray(new String[0]);
        final String[] wpValues = valuesList.toArray(new String[0]);

        if (!valuesList.contains(wpChoice)) {
            wpChoice = "random";
            sp.edit().putString(Config.WALLPAPER_CHOICE_KEY, "random").apply();
        }

        addChoiceRow("Обои рабочего стола", "Выберите картину для фона или слайдшоу", getWallpaperDisplayName(wpChoice, rotMinutes), (card, cardIdx) -> {
            String currentWp = sp.getString(Config.WALLPAPER_CHOICE_KEY, "random");
            showChoiceDialog("Обои рабочего стола", wpLabels, wpValues, currentWp, card, (val, lbl) -> {
                sp.edit().putString(Config.WALLPAPER_CHOICE_KEY, val).apply();
                markChanged();
                int currentRot = sp.getInt("wallpaper_rotation_minutes", 15);
                setCardValue(card, getWallpaperDisplayName(val, currentRot));
                if (rotCardRef[0] != null) {
                    rotCardRef[0].setVisibility("random".equalsIgnoreCase(val) ? View.VISIBLE : View.GONE);
                }
            });
        });

        String rotLabel = rotMinutes == 0 ? "При каждом запуске" : rotMinutes + " мин";
        addChoiceRow("Интервал смены обоев", "Частота ротации случайных картин", rotLabel, (card, cardIdx) -> {
            int curRot = sp.getInt("wallpaper_rotation_minutes", 15);
            showChoiceDialog("Интервал смены обоев", new String[]{
                    "15 минут (рекомендуется)",
                    "30 минут",
                    "1 час (60 мин)",
                    "3 часа (180 мин)",
                    "При каждом запуске лаунчера"
            }, new String[]{"15", "30", "60", "180", "0"}, String.valueOf(curRot), card, (val, lbl) -> {
                int newRot = Integer.parseInt(val);
                sp.edit().putInt("wallpaper_rotation_minutes", newRot).apply();
                markChanged();
                setCardValue(card, newRot == 0 ? "При каждом запуске" : newRot + " мин");
            });
        });
        rotCardRef[0] = contentCards.get(contentCards.size() - 1);
        if (!"random".equalsIgnoreCase(wpChoice)) {
            rotCardRef[0].setVisibility(View.GONE);
        }

        addHeader("АВТООБНОВЛЕНИЕ ОБОЕВ");

        int syncDays = sp.getInt("wallpaper_sync_days", 7);
        String syncLabel;
        if (syncDays == 7) syncLabel = "Каждые 7 дней (рекомендуется)";
        else if (syncDays == 10) syncLabel = "Каждые 10 дней";
        else if (syncDays == 14) syncLabel = "Каждые 14 дней";
        else syncLabel = "Выключено";

        addChoiceRow("Период обновления", "Как часто проверять новые картины из облака", syncLabel, (card, cardIdx) -> {
            int curDays = sp.getInt("wallpaper_sync_days", 7);
            showChoiceDialog("Период обновления обоев", new String[]{
                    "Каждые 7 дней (рекомендуется)",
                    "Каждые 10 дней",
                    "Каждые 14 дней",
                    "Выключено"
            }, new String[]{"7", "10", "14", "0"}, String.valueOf(curDays), card, (val, lbl) -> {
                int days = Integer.parseInt(val);
                sp.edit().putInt("wallpaper_sync_days", days).apply();
                markChanged();
                String l = days == 7 ? "Каждые 7 дней (рекомендуется)" : (days == 10 ? "Каждые 10 дней" : (days == 14 ? "Каждые 14 дней" : "Выключено"));
                setCardValue(card, l);
            });
        });

        addActionRow("Источники и лицензии", "Авторы и лицензии CC BY-SA из Wikimedia Commons", "Смотреть ›", this::showCreditsDialog);

        addHeader("ПРОЗРАЧНОСТЬ И ЭФФЕКТЫ");

        int glassOpacity = sp.getInt("settings_glass_opacity", 50);
        String opacityLabel = glassOpacity + "%" + (glassOpacity == 50 ? " (Tahoe 27.2)" : "");
        addChoiceRow("Прозрачность окна настроек", "Интенсивность матового стекла поверх Home", opacityLabel, (card, cardIdx) -> {
            int curOp = sp.getInt("settings_glass_opacity", 50);
            showChoiceDialog("Прозрачность матового стекла", new String[]{
                    "30% (Высокая прозрачность — видно весь экран)",
                    "50% (Сбалансированное стекло Tahoe 27.2 — стандарт)",
                    "70% (Плотное матовое стекло)",
                    "85% (Глубокое матовое стекло)",
                    "100% (Непрозрачный тёмный фон)"
            }, new String[]{"30", "50", "70", "85", "100"}, String.valueOf(curOp), card, (val, lbl) -> {
                int op = Integer.parseInt(val);
                sp.edit().putInt("settings_glass_opacity", op).apply();
                updateGlassScrim();
                setCardValue(card, op + "%" + (op == 50 ? " (Tahoe 27.2)" : ""));
            });
        });

        boolean advanced = sp.getBoolean("advanced_effects", true);
        addToggleRow("Эффект матового стекла (Liquid Glass)", "Полупрозрачные подложки дока и виджетов", advanced, val -> {
            sp.edit().putBoolean("advanced_effects", val).apply();
            markChanged();
        });

        boolean anims = sp.getBoolean("animations", true);
        addToggleRow("Плавные анимации интерфейса", "Масштабирование фокуса и плавный сдвиг", anims, val -> {
            sp.edit().putBoolean("animations", val).apply();
            markChanged();
        });

        addHeader("ЧАСЫ И ВРЕМЯ");

        String clockFmt = sp.getString(Config.CLOCK_FORMAT_KEY, "24");
        String clockFmtLabel = "24".equals(clockFmt) ? "24-часовой (15:30)" : "12-часовой (3:30 PM)";
        addChoiceRow("Формат времени", "24 или 12 часов с индикатором AM/PM", clockFmtLabel, (card, cardIdx) -> {
            String curFmt = sp.getString(Config.CLOCK_FORMAT_KEY, "24");
            showChoiceDialog("Формат времени", new String[]{
                    "24-часовой (15:30)",
                    "12-часовой (3:30 PM)",
                    "Как в системе Android"
            }, new String[]{"24", "12", "system"}, curFmt, card, (val, lbl) -> {
                sp.edit().putString(Config.CLOCK_FORMAT_KEY, val).apply();
                markChanged();
                setCardValue(card, "24".equals(val) ? "24-часовой (15:30)" : "12-часовой (3:30 PM)");
            });
        });

        boolean clockLarge = sp.getBoolean("clock_large", true);
        addToggleRow("Крупные часы в стиле Apple TV", "Размер шрифта часов на экране", clockLarge, val -> {
            sp.edit().putBoolean("clock_large", val).apply();
            markChanged();
        });
    }

    // --------------------------------------------------------------------------
    // 2. ПОГОДА И ГОРОД
    // --------------------------------------------------------------------------
    private void buildWeatherSection() {
        addHeader("ГОРОД ДЛЯ ПРОГНОЗА");

        SharedPreferences wp = getSharedPreferences(PREF_WEATHER, MODE_PRIVATE);
        String currentCity = wp.getString("city_name", "Худжанд");

        addChoiceRow("Выбрать город", "Автоматический точный прогноз температуры и погоды", currentCity, (card, cardIdx) -> {
            String curCity = wp.getString("city_name", "Худжанд");
            showChoiceDialog("Город для прогноза", new String[]{
                    "Худжанд (Таджикистан)",
                    "Душанбе (Таджикистан)",
                    "Ташкент (Узбекистан)",
                    "Самарканд (Узбекистан)",
                    "Москва (Россия)",
                    "Казань (Россия)",
                    "Санкт-Петербург (Россия)"
            }, new String[]{"Худжанд", "Душанбе", "Ташкент", "Самарканд", "Москва", "Казань", "Санкт-Петербург"}, curCity, card, (val, lbl) -> {
                wp.edit().putString("city_name", val).putLong("last_timestamp", 0L).apply();
                markChanged();
                setCardValue(card, val);
            });
        });

        int interval = wp.getInt("interval_minutes", 20);
        addChoiceRow("Интервал обновления", "Частота запроса погоды через интернет", interval + " мин", (card, cardIdx) -> {
            int curInt = wp.getInt("interval_minutes", 20);
            showChoiceDialog("Интервал обновления", new String[]{
                    "15 минут", "20 минут (по умолчанию)", "30 минут", "60 минут"
            }, new String[]{"15", "20", "30", "60"}, String.valueOf(curInt), card, (val, lbl) -> {
                int newInt = Integer.parseInt(val);
                wp.edit().putInt("interval_minutes", newInt).apply();
                markChanged();
                setCardValue(card, newInt + " мин");
            });
        });

        addHeader("ОТОБРАЖАЕМЫЕ ПАРАМЕТРЫ");

        boolean showHum = wp.getBoolean("show_humidity", true);
        addToggleRow("Процент влажности (%)", "Текущая влажность воздуха в городе", showHum, val -> {
            wp.edit().putBoolean("show_humidity", val).putLong("last_timestamp", 0L).apply();
            markChanged();
        });

        boolean showWind = wp.getBoolean("show_wind", true);
        addToggleRow("Скорость ветра (м/с)", "Скорость порывов ветра", showWind, val -> {
            wp.edit().putBoolean("show_wind", val).putLong("last_timestamp", 0L).apply();
            markChanged();
        });

        boolean showSun = wp.getBoolean("show_sun", true);
        addToggleRow("Время восхода и заката", "Иконки 🌅 рассвета и 🌇 заката", showSun, val -> {
            wp.edit().putBoolean("show_sun", val).putLong("last_timestamp", 0L).apply();
            markChanged();
        });
    }

    // --------------------------------------------------------------------------
    // 3. ВРЕМЕНА НАМАЗА
    // --------------------------------------------------------------------------
    private void buildPrayerSection() {
        addHeader("РАСПИСАНИЕ НАМАЗА");

        SharedPreferences pp = getSharedPreferences(PREF_PRAYER, MODE_PRIVATE);
        boolean enabled = pp.getBoolean("prayer_enabled", true);
        addToggleRow("Показывать блок намаза", "Виджет 5 времен намаза справа под кнопками", enabled, val -> {
            pp.edit().putBoolean("prayer_enabled", val).apply();
            markChanged();
        });

        String madhab = pp.getString("madhab", "hanafi");
        String madhabLabel = "hanafi".equals(madhab) ? "Ханафитский (2x тень Аср)" : "Шафиитский (1x тень)";
        addChoiceRow("Мазхаб (расчет времени Асра)", "Метод вычисления времени наступления Асра", madhabLabel, (card, cardIdx) -> {
            String curMad = pp.getString("madhab", "hanafi");
            showChoiceDialog("Мазхаб", new String[]{
                    "Ханафитский (двойная тень — стандарт региона)",
                    "Шафиитский / Ханбалитский / Маликитский"
            }, new String[]{"hanafi", "shafii"}, curMad, card, (val, lbl) -> {
                pp.edit().putString("madhab", val).remove("times_" + getTodayKey()).apply();
                markChanged();
                setCardValue(card, "hanafi".equals(val) ? "Ханафитский (2x тень Аср)" : "Шафиитский (1x тень)");
            });
        });

        boolean countdown = pp.getBoolean("show_countdown", true);
        addToggleRow("Обратный отсчет до следующего намаза", "Например: «ДО АСРА 15 МИН»", countdown, val -> {
            pp.edit().putBoolean("show_countdown", val).apply();
            markChanged();
        });

        addHeader("ТОЧНАЯ КОРРЕКТИРОВКА");

        int fajrOffset = pp.getInt("fajr_offset", 0);
        addChoiceRow("Корректировка Фаджра", "Смещение времени утреннего намаза", (fajrOffset >= 0 ? "+" : "") + fajrOffset + " мин", (card, cardIdx) -> {
            int curOff = pp.getInt("fajr_offset", 0);
            showChoiceDialog("Корректировка Фаджра", new String[]{
                    "-5 минут", "0 минут (по астрономическому расчету)", "+5 минут", "+10 минут"
            }, new String[]{"-5", "0", "5", "10"}, String.valueOf(curOff), card, (val, lbl) -> {
                int off = Integer.parseInt(val);
                pp.edit().putInt("fajr_offset", off).remove("times_" + getTodayKey()).apply();
                markChanged();
                setCardValue(card, (off >= 0 ? "+" : "") + off + " мин");
            });
        });

        int asrOffset = pp.getInt("asr_offset", 0);
        addChoiceRow("Корректировка Асра", "Смещение времени послеполуденного намаза", (asrOffset >= 0 ? "+" : "") + asrOffset + " мин", (card, cardIdx) -> {
            int curOff = pp.getInt("asr_offset", 0);
            showChoiceDialog("Корректировка Асра", new String[]{
                    "-10 минут", "-5 минут", "0 минут (по длине тени)", "+5 минут", "+10 минут"
            }, new String[]{"-10", "-5", "0", "5", "10"}, String.valueOf(curOff), card, (val, lbl) -> {
                int off = Integer.parseInt(val);
                pp.edit().putInt("asr_offset", off).remove("times_" + getTodayKey()).apply();
                markChanged();
                setCardValue(card, (off >= 0 ? "+" : "") + off + " мин");
            });
        });

        int maghribOffset = pp.getInt("maghrib_offset", 0);
        addChoiceRow("Корректировка Магриба", "Смещение времени вечернего намаза (разговения)", (maghribOffset >= 0 ? "+" : "") + maghribOffset + " мин", (card, cardIdx) -> {
            int curOff = pp.getInt("maghrib_offset", 0);
            showChoiceDialog("Корректировка Магриба", new String[]{
                    "-3 минуты", "0 минут (по заходу солнца)", "+3 минуты", "+5 минут"
            }, new String[]{"-3", "0", "3", "5"}, String.valueOf(curOff), card, (val, lbl) -> {
                int off = Integer.parseInt(val);
                pp.edit().putInt("maghrib_offset", off).remove("times_" + getTodayKey()).apply();
                markChanged();
                setCardValue(card, (off >= 0 ? "+" : "") + off + " мин");
            });
        });
    }

    // --------------------------------------------------------------------------
    // 4. РЕКОМЕНДАЦИИ И КИНО (WATCH NEXT)
    // --------------------------------------------------------------------------
    private void buildShelfSection() {
        addHeader("ПОЛКА КОНТЕНТА В ЦЕНТРЕ");

        SharedPreferences sp = getSharedPreferences(PREF_WATCH, MODE_PRIVATE);
        boolean shelfEnabled = sp.getBoolean("shelf_enabled", true);
        addToggleRow("Показывать блок по центру экрана", "Горизонтальная лента с постерами новинок и просмотров", shelfEnabled, val -> {
            sp.edit().putBoolean("shelf_enabled", val).apply();
            markChanged();
        });

        String mode = sp.getString("shelf_mode", "all");
        String modeLabel = "all".equals(mode) ? "Новинки кино + Продолжить просмотр"
                : "continue_only".equals(mode) ? "Только продолжить просмотр" : "Только новинки кино";

        addChoiceRow("Режим отображения ленты", "Что выводить в центральном ряду", modeLabel, (card, cardIdx) -> {
            String curMode = sp.getString("shelf_mode", "all");
            showChoiceDialog("Режим ленты", new String[]{
                    "Новинки кино + Продолжить просмотр (рекомендуется)",
                    "Только продолжить просмотр (скрывать если пусто)",
                    "Только новинки кино"
            }, new String[]{"all", "continue_only", "recs_only"}, curMode, card, (val, lbl) -> {
                sp.edit().putString("shelf_mode", val).apply();
                markChanged();
                setCardValue(card, "all".equals(val) ? "Новинки кино + Продолжить просмотр"
                        : "continue_only".equals(val) ? "Только продолжить просмотр" : "Только новинки кино");
            });
        });

        int recsHours = sp.getInt("recs_interval_hours", 6);
        String recsLabel = recsHours == 0 ? "При каждом запуске" : ("Каждые " + recsHours + " ч");
        addChoiceRow("Интервал обновления новинок", "Периодичность смены постеров и рекомендаций", recsLabel, (card, cardIdx) -> {
            int curHours = sp.getInt("recs_interval_hours", 6);
            showChoiceDialog("Интервал обновления новинок", new String[]{
                    "Каждые 6 часов (рекомендуется)",
                    "Каждые 12 часов",
                    "Каждые 18 часов",
                    "Раз в сутки (24 часа)",
                    "При каждом запуске лаунчера"
            }, new String[]{"6", "12", "18", "24", "0"}, String.valueOf(curHours), card, (val, lbl) -> {
                int h = Integer.parseInt(val);
                sp.edit().putInt("recs_interval_hours", h).apply();
                ReleasesManager.invalidateCache();
                markChanged();
                setCardValue(card, h == 0 ? "При каждом запуске" : ("Каждые " + h + " ч"));
            });
        });

        addHeader("ЗАПУСК ФИЛЬМОВ");

        String defaultPlayer = sp.getString("default_player", "auto");
        String playerLabel = "auto".equals(defaultPlayer) ? "Автовыбор (Zona)"
                : "zona".equals(defaultPlayer) ? "Zona (прямой запуск)"
                : "ivi".equals(defaultPlayer) ? "Ivi (если доступен ID)"
                : "rezka".equals(defaultPlayer) ? "HDRezka (главный экран)"
                : "kinotrek".equals(defaultPlayer) ? "Кинотрек (главный экран)" : "VK Видео (главный экран)";

        addChoiceRow("Приложение для открытия новинок", "В каком онлайн-кинотеатре запускать фильм", playerLabel, (card, cardIdx) -> {
            String curPlayer = sp.getString("default_player", "auto");
            showChoiceDialog("Приложение для открытия", new String[]{
                    "Автовыбор (Zona — прямой запуск)",
                    "Zona (прямой запуск по ID Кинопоиска)",
                    "Ivi (только если доступен ID)",
                    "HDRezka (только открыть приложение)",
                    "Кинотрек (только открыть приложение)",
                    "VK Видео (только открыть приложение)"
            }, new String[]{"auto", "zona", "ivi", "rezka", "kinotrek", "vk"}, curPlayer, card, (val, lbl) -> {
                sp.edit().putString("default_player", val).apply();
                markChanged();
                setCardValue(card, "auto".equals(val) ? "Автовыбор (Zona)"
                        : "zona".equals(val) ? "Zona (прямой запуск)"
                        : "ivi".equals(val) ? "Ivi (если доступен ID)"
                        : "rezka".equals(val) ? "HDRezka (главный экран)"
                        : "kinotrek".equals(val) ? "Кинотрек (главный экран)" : "VK Видео (главный экран)");
            });
        });
    }

    // --------------------------------------------------------------------------
    // 5. ДОК И ПРИЛОЖЕНИЯ
    // --------------------------------------------------------------------------
    private void buildDockSection() {
        addHeader("НИЖНЯЯ ПАНЕЛЬ ПРИЛОЖЕНИЙ (ДОК)");

        SharedPreferences sp = getSharedPreferences(PREF_SETTINGS, MODE_PRIVATE);
        int cols = sp.getInt("columns", 6);

        addChoiceRow("Количество приложений в ряду", "Сколько иконок помещается в стеклянном доке", cols + " приложений", (card, cardIdx) -> {
            int curCols = sp.getInt("columns", 6);
            showChoiceDialog("Количество приложений", new String[]{
                    "5 приложений (крупные иконки)",
                    "6 приложений (сбалансировано)",
                    "7 приложений (компактные)"
            }, new String[]{"5", "6", "7"}, String.valueOf(curCols), card, (val, lbl) -> {
                int c = Integer.parseInt(val);
                sp.edit().putInt("columns", c).apply();
                markChanged();
                setCardValue(card, c + " приложений");
            });
        });

        boolean showHero = sp.getBoolean("show_hero_label", true);
        addToggleRow("Название приложения над доком", "Крупная надпись активного приложения (например «VK Видео»)", showHero, val -> {
            sp.edit().putBoolean("show_hero_label", val).apply();
            markChanged();
        });

        int opacity = sp.getInt(Config.DOCK_OPACITY_KEY, 100);
        addChoiceRow("Прозрачность стекла дока", "Интенсивность матовой подложки", opacity + "%", (card, cardIdx) -> {
            int curOp = sp.getInt(Config.DOCK_OPACITY_KEY, 100);
            showChoiceDialog("Прозрачность дока", new String[]{
                    "100% (Матовое стекло Apple)",
                    "70% (Умеренная)",
                    "50% (Полупрозрачная)",
                    "30% (Почти невидимая)"
            }, new String[]{"100", "70", "50", "30"}, String.valueOf(curOp), card, (val, lbl) -> {
                int op = Integer.parseInt(val);
                sp.edit().putInt(Config.DOCK_OPACITY_KEY, op).apply();
                markChanged();
                setCardValue(card, op + "%");
            });
        });

        addHeader("ПОРЯДОК И ИКОНКИ");

        addActionRow("Изменить порядок иконок на экране", "Режим перетаскивания и скрытия приложений", "Настроить ›", () -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
    }

    // --------------------------------------------------------------------------
    // 6. БЫСТРЫЕ КНОПКИ (TOP BAR)
    // --------------------------------------------------------------------------
    private void buildButtonsSection() {
        addHeader("КРУГЛЫЕ КНОПКИ СПРАВА ВВЕРХУ");

        SharedPreferences sp = getSharedPreferences(PREF_SETTINGS, MODE_PRIVATE);

        boolean btnInputs = sp.getBoolean("btn_sources", true);
        addToggleRow("Кнопка «Источники (HDMI)»", "Быстрое переключение на HDMI 1 / HDMI 2 / AV", btnInputs, val -> {
            sp.edit().putBoolean("btn_sources", val).apply();
            markChanged();
        });

        boolean btnWifi = sp.getBoolean("btn_wifi", true);
        addToggleRow("Кнопка «Wi-Fi и сеть»", "Быстрое подключение и статус сети", btnWifi, val -> {
            sp.edit().putBoolean("btn_wifi", val).apply();
            markChanged();
        });

        boolean btnBt = sp.getBoolean("btn_bluetooth", true);
        addToggleRow("Кнопка «Bluetooth»", "Быстрое сопряжение наушников, колонок и пультов", btnBt, val -> {
            sp.edit().putBoolean("btn_bluetooth", val).apply();
            markChanged();
        });

        boolean btnSet = sp.getBoolean("btn_settings", true);
        addToggleRow("Кнопка «Настройки»", "Прямой переход в настройки лаунчера", btnSet, val -> {
            sp.edit().putBoolean("btn_settings", val).apply();
            markChanged();
        });

        addHeader("ОЧЕРЕДНОСТЬ И РАСПОЛОЖЕНИЕ КНОПОК");

        String orderStr = sp.getString("quick_buttons_order", "inputs,wifi,bluetooth,settings");
        final String[] slots = parseButtonSlots(orderStr);
        final View[] slotCards = new View[4];
        final View[] presetCardRef = new View[1];

        addChoiceRow("Схема очередности", "Быстрый выбор популярного порядка", getOrderPresetTitle(orderStr), (card, cardIdx) -> {
            String curOrder = sp.getString("quick_buttons_order", "inputs,wifi,bluetooth,settings");
            showChoiceDialog("Схема очередности кнопок", new String[]{
                    "HDMI → Wi-Fi → Bluetooth → Настройки (стандарт)",
                    "Bluetooth → Wi-Fi → HDMI → Настройки",
                    "Wi-Fi → Bluetooth → HDMI → Настройки",
                    "Bluetooth → HDMI → Wi-Fi → Настройки",
                    "Настройки → Bluetooth → Wi-Fi → HDMI",
                    "HDMI → Bluetooth → Wi-Fi → Настройки"
            }, new String[]{
                    "inputs,wifi,bluetooth,settings",
                    "bluetooth,wifi,inputs,settings",
                    "wifi,bluetooth,inputs,settings",
                    "bluetooth,inputs,wifi,settings",
                    "settings,bluetooth,wifi,inputs",
                    "inputs,bluetooth,wifi,settings"
            }, curOrder, card, (val, lbl) -> {
                sp.edit().putString("quick_buttons_order", val).apply();
                markChanged();
                setCardValue(card, getOrderPresetTitle(val));
                String[] newSlots = parseButtonSlots(val);
                for (int i = 0; i < 4; i++) {
                    slots[i] = newSlots[i];
                    if (slotCards[i] != null) {
                        setCardValue(slotCards[i], getButtonDisplayName(newSlots[i]));
                    }
                }
            });
        });
        presetCardRef[0] = contentCards.get(contentCards.size() - 1);

        String[] slotTitles = {"1-я кнопка (слева)", "2-я кнопка", "3-я кнопка", "4-я кнопка (справа)"};
        for (int i = 0; i < 4; i++) {
            final int pos = i;
            addChoiceRow(slotTitles[pos], "Поменять местами кнопку на позиции " + (pos + 1), getButtonDisplayName(slots[pos]), (card, cardIdx) -> {
                showChoiceDialog("Кнопка на позиции " + (pos + 1), new String[]{
                        "Источники (HDMI)",
                        "Wi-Fi и сеть",
                        "Bluetooth",
                        "Настройки"
                }, new String[]{
                        "inputs",
                        "wifi",
                        "bluetooth",
                        "settings"
                }, slots[pos], card, (val, lbl) -> {
                    if (!val.equals(slots[pos])) {
                        int otherPos = -1;
                        for (int j = 0; j < 4; j++) {
                            if (val.equals(slots[j])) {
                                otherPos = j;
                                break;
                            }
                        }
                        if (otherPos != -1) {
                            String temp = slots[pos];
                            slots[pos] = val;
                            slots[otherPos] = temp;
                            if (slotCards[otherPos] != null) {
                                setCardValue(slotCards[otherPos], getButtonDisplayName(temp));
                            }
                        } else {
                            slots[pos] = val;
                        }
                        String newOrder = TextUtils.join(",", slots);
                        sp.edit().putString("quick_buttons_order", newOrder).apply();
                        markChanged();
                        setCardValue(card, getButtonDisplayName(val));
                        if (presetCardRef[0] != null) {
                            setCardValue(presetCardRef[0], getOrderPresetTitle(newOrder));
                        }
                    }
                });
            });
            slotCards[pos] = contentCards.get(contentCards.size() - 1);
        }
    }

    private static String[] parseButtonSlots(String orderStr) {
        String[] defaults = {"inputs", "wifi", "bluetooth", "settings"};
        if (TextUtils.isEmpty(orderStr)) return defaults;
        String[] parts = orderStr.split(",");
        String[] res = new String[4];
        Set<String> used = new HashSet<>();
        int count = 0;
        for (String p : parts) {
            String clean = p.trim();
            if (!clean.isEmpty() && !used.contains(clean) && count < 4) {
                res[count++] = clean;
                used.add(clean);
            }
        }
        for (String d : defaults) {
            if (count < 4 && !used.contains(d)) {
                res[count++] = d;
                used.add(d);
            }
        }
        return res;
    }

    private static String getOrderPresetTitle(String orderStr) {
        if ("inputs,wifi,bluetooth,settings".equals(orderStr)) return "HDMI → Wi-Fi → BT → Настройки";
        if ("bluetooth,wifi,inputs,settings".equals(orderStr)) return "BT → Wi-Fi → HDMI → Настройки";
        if ("wifi,bluetooth,inputs,settings".equals(orderStr)) return "Wi-Fi → BT → HDMI → Настройки";
        if ("bluetooth,inputs,wifi,settings".equals(orderStr)) return "BT → HDMI → Wi-Fi → Настройки";
        if ("settings,bluetooth,wifi,inputs".equals(orderStr)) return "Настройки → BT → Wi-Fi → HDMI";
        if ("inputs,bluetooth,wifi,settings".equals(orderStr)) return "HDMI → BT → Wi-Fi → Настройки";
        return "Индивидуальный порядок";
    }

    private static String getButtonDisplayName(String key) {
        if ("inputs".equals(key)) return "Источники (HDMI)";
        if ("wifi".equals(key)) return "Wi-Fi и сеть";
        if ("bluetooth".equals(key)) return "Bluetooth";
        if ("settings".equals(key)) return "Настройки";
        return key;
    }

    // --------------------------------------------------------------------------
    // 7. СИСТЕМА И ПУЛЬТ
    // --------------------------------------------------------------------------
    private void buildSystemSection() {
        addHeader("ПУЛЬТ И ПОВЕДЕНИЕ КНОПКИ HOME");

        SharedPreferences sp = getSharedPreferences(PREF_SETTINGS, MODE_PRIVATE);
        boolean homeHijack = sp.getBoolean("home_key_hijack", true);
        addToggleRow("Перехват кнопки Home на пульте", "При нажатии кнопки Домой всегда открывать SmartTV Hub", homeHijack, val -> {
            sp.edit().putBoolean("home_key_hijack", val).apply();
            markChanged();
        });

        addHeader("СИСТЕМНЫЕ ДЕЙСТВИЯ");

        addActionRow("Заводские настройки телевизора", "Открыть стандартное меню настроек Android TV", "Открыть ›", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(this, "Системные настройки недоступны", Toast.LENGTH_SHORT).show();
            }
        });

        addActionRow("Очистить кэш постеров и памяти", "Удаляет сохраненные изображения и освобождает место", "Очистить ›", () -> {
            try {
                File dir = new File(getFilesDir(), "cw");
                if (dir.exists()) {
                    File[] files = dir.listFiles();
                    if (files != null) for (File f : files) f.delete();
                }
                File recsCache = new File(getFilesDir(), "releases_cache.json");
                if (recsCache.exists()) recsCache.delete();
                Toast.makeText(this, "Кэш успешно очищен", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        });
    }

    // --------------------------------------------------------------------------
    // 8. О ЛАУНЧЕРЕ
    // --------------------------------------------------------------------------
    private void buildAboutSection() {
        addHeader("ИНФОРМАЦИЯ О ЛАУНЧЕРЕ");

        addInfoRow("Версия приложения", "SmartTV Hub v2.2 (Apple tvOS Edition)");
        addInfoRow("Телевизор", "Salute TV · Android 11 (API 30)");
        addInfoRow("Разрешение экрана", "1920 × 1080 (320 dpi)");

        boolean sessionRunning = MediaWatchService.granted(this);
        addInfoRow("Служба отслеживания сессий", sessionRunning ? "Активна (MediaSession)" : "Ожидает разрешений");

        addHeader("СБРОС");

        addActionRow("Сбросить все настройки по умолчанию", "Возвращает стандартные параметры лаунчера", "Сброс ›", () -> {
            new AlertDialog.Builder(this)
                    .setTitle("Сбросить настройки?")
                    .setMessage("Все параметры внешнего вида и виджетов вернутся к стандартным.")
                    .setPositiveButton("Сбросить", (d, w) -> {
                        getSharedPreferences(PREF_SETTINGS, MODE_PRIVATE).edit().clear().apply();
                        getSharedPreferences(PREF_WEATHER, MODE_PRIVATE).edit().clear().apply();
                        getSharedPreferences(PREF_PRAYER, MODE_PRIVATE).edit().clear().apply();
                        getSharedPreferences(PREF_WATCH, MODE_PRIVATE).edit().clear().apply();
                        markChanged();
                        updateGlassScrim();
                        selectSection(SEC_APPEARANCE, false);
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        });
    }

    // --------------------------------------------------------------------------
    // UI HELPERS (Headers, Cards, Dialogs)
    // --------------------------------------------------------------------------
    private void addHeader(String title) {
        TextView h = new TextView(this);
        h.setText(title);
        h.setTextColor(0x80FFFFFF);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        h.setLetterSpacing(0.12f);
        h.setPadding(dp(14), dp(14), dp(14), dp(6));
        rightList.addView(h);
    }

    private void addToggleRow(String title, String subtitle, boolean checked, OnToggleListener listener) {
        final boolean[] state = new boolean[]{checked};
        View card = createCardView(title, subtitle, state[0] ? "Вкл" : "Выкл", state[0] ? 0xFF30D158 : 0x88FFFFFF);
        card.setOnClickListener(v -> {
            v.playSoundEffect(SoundEffectConstants.CLICK);
            state[0] = !state[0];
            listener.onToggle(state[0]);
            TextView valTv = card.findViewById(android.R.id.text2);
            if (valTv != null) {
                valTv.setText(state[0] ? "Вкл" : "Выкл");
                valTv.setTextColor(state[0] ? 0xFF30D158 : 0x88FFFFFF);
            }
        });
        rightList.addView(card);
        contentCards.add(card);
    }

    private void addChoiceRow(String title, String subtitle, String currentValue, ChoiceOpener opener) {
        final int thisIdx = contentCards.size();
        View card = createCardView(title, subtitle, currentValue + "  ›", 0xC0FFFFFF);
        card.setOnClickListener(v -> {
            v.playSoundEffect(SoundEffectConstants.CLICK);
            opener.open(card, thisIdx);
        });
        rightList.addView(card);
        contentCards.add(card);
    }

    private static void setCardValue(View card, String text) {
        if (card != null) {
            TextView valTv = card.findViewById(android.R.id.text2);
            if (valTv != null) {
                valTv.setText(text + "  ›");
            }
        }
    }

    private void addActionRow(String title, String subtitle, String actionText, Runnable onClick) {
        View card = createCardView(title, subtitle, actionText, 0xFF8FD3FF);
        card.setOnClickListener(v -> {
            v.playSoundEffect(SoundEffectConstants.CLICK);
            onClick.run();
        });
        rightList.addView(card);
        contentCards.add(card);
    }

    private void addInfoRow(String title, String value) {
        View card = createCardView(title, "", value, 0x99FFFFFF);
        card.setFocusable(false);
        card.setClickable(false);
        rightList.addView(card);
    }

    private View createCardView(String title, String subtitle, String value, int valueColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x281F2633); // Tahoe frosted translucent glass card
        bg.setStroke(dp(1), 0x24FFFFFF);
        row.setBackground(bg);

        LinearLayout leftBox = new LinearLayout(this);
        leftBox.setOrientation(LinearLayout.VERTICAL);
        leftBox.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleTv = new TextView(this);
        titleTv.setId(android.R.id.text1);
        titleTv.setText(title);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        leftBox.addView(titleTv);

        if (!TextUtils.isEmpty(subtitle)) {
            TextView subTv = new TextView(this);
            subTv.setText(subtitle);
            subTv.setTextColor(0x8CFFFFFF);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            subTv.setSingleLine(true);
            subTv.setEllipsize(TextUtils.TruncateAt.END);
            leftBox.addView(subTv);
        }

        row.addView(leftBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView valTv = new TextView(this);
        valTv.setId(android.R.id.text2);
        valTv.setText(value);
        valTv.setTextColor(valueColor);
        valTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        valTv.setSingleLine(true);
        row.addView(valTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnFocusChangeListener((v, hasFocus) -> {
            GradientDrawable d = (GradientDrawable) v.getBackground();
            if (hasFocus) {
                lastFocusedCard = v;
                d.setColor(0x44FFFFFF);
                d.setStroke(dp(2.5f), Color.WHITE);
                v.setScaleX(1.02f);
                v.setScaleY(1.02f);
                v.setTranslationZ(dp(6));
                v.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
            } else {
                d.setColor(0x281F2633);
                d.setStroke(dp(1), 0x24FFFFFF);
                v.setScaleX(1.0f);
                v.setScaleY(1.0f);
                v.setTranslationZ(0f);
            }
            updateSidebarState();
        });

        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (activeSection >= 0 && activeSection < sideRows.size()) {
                    lastFocusedCard = null;
                    sideRows.get(activeSection).requestFocus();
                    return true;
                }
            }
            return false;
        });

        return row;
    }

    private void showChoiceDialog(String title, String[] labels, String[] values, String selectedValue, View callerCard, OnChoiceSelected listener) {
        isDialogOpen = true;
        if (callerCard != null) {
            lastFocusedCard = callerCard;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(20), dp(24), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFA131924); // Tahoe dark frosted glass dialog
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1.5f), 0x4DFFFFFF);
        box.setBackground(bg);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setPadding(dp(8), dp(4), dp(8), dp(16));
        box.addView(titleView);

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout itemsBox = new LinearLayout(this);
        itemsBox.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(itemsBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));

        AlertDialog dialog = builder.setView(box).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        View initialFocus = null;

        for (int i = 0; i < labels.length; i++) {
            final String val = values[i];
            final String lbl = labels[i];
            final boolean isChecked = val.equals(selectedValue);

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setFocusable(true);
            item.setClickable(true);
            item.setPadding(dp(16), dp(12), dp(16), dp(12));

            GradientDrawable iBg = new GradientDrawable();
            iBg.setCornerRadius(dp(12));
            iBg.setColor(isChecked ? 0x24FFFFFF : 0x12FFFFFF);
            iBg.setStroke(dp(1), isChecked ? 0x66FFFFFF : Color.TRANSPARENT);
            item.setBackground(iBg);

            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            iLp.bottomMargin = dp(6);
            item.setLayoutParams(iLp);

            TextView text = new TextView(this);
            text.setText(lbl);
            text.setTextColor(Color.WHITE);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            item.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (isChecked) {
                ImageView check = new ImageView(this);
                check.setImageBitmap(Glyphs.get("check_on", dp(18), Color.WHITE));
                item.addView(check, new LinearLayout.LayoutParams(dp(18), dp(18)));
                initialFocus = item;
            }

            item.setOnFocusChangeListener((v, hasFocus) -> {
                GradientDrawable d = (GradientDrawable) v.getBackground();
                if (hasFocus) {
                    d.setColor(0x38FFFFFF);
                    d.setStroke(dp(2), Color.WHITE);
                    v.setScaleX(1.02f);
                    v.setScaleY(1.02f);
                } else {
                    d.setColor(val.equals(selectedValue) ? 0x24FFFFFF : 0x12FFFFFF);
                    d.setStroke(dp(1), val.equals(selectedValue) ? 0x66FFFFFF : Color.TRANSPARENT);
                    v.setScaleX(1.0f);
                    v.setScaleY(1.0f);
                }
            });

            item.setOnClickListener(v -> {
                v.playSoundEffect(SoundEffectConstants.CLICK);
                dialog.dismiss();
                listener.onSelected(val, lbl);
                if (callerCard != null) {
                    lastFocusedCard = callerCard;
                    callerCard.post(() -> callerCard.requestFocus());
                }
            });

            itemsBox.addView(item);
        }

        dialog.setOnDismissListener(d -> {
            final View target = callerCard != null ? callerCard : lastFocusedCard;
            if (target != null) {
                target.post(() -> target.requestFocus());
                target.postDelayed(() -> {
                    target.requestFocus();
                    isDialogOpen = false;
                    updateSidebarState();
                }, 80);
            } else {
                isDialogOpen = false;
                updateSidebarState();
            }
        });

        final View toFocus = initialFocus != null ? initialFocus : (itemsBox.getChildCount() > 0 ? itemsBox.getChildAt(0) : null);
        dialog.setOnShowListener(d -> {
            if (toFocus != null) {
                scroller.post(() -> {
                    toFocus.requestFocus();
                    scroller.smoothScrollTo(0, toFocus.getTop());
                });
            }
        });

        dialog.show();
    }

    private void showCreditsDialog() {
        File dir = Config.dir(this);
        File creditsFile = dir == null ? null : new File(dir, "wallpapers/credits.json");
        StringBuilder sb = new StringBuilder();
        if (creditsFile != null && creditsFile.isFile()) {
            try {
                String jsonStr = new String(Files.readAllBytes(creditsFile.toPath()), StandardCharsets.UTF_8);
                JSONObject obj = new JSONObject(jsonStr);
                JSONArray arr = obj.optJSONArray("credits");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.getJSONObject(i);
                        String title = c.optString("title", "");
                        String author = c.optString("author", "");
                        String license = c.optString("license", "");
                        sb.append(i + 1).append(". ").append(title).append("\n");
                        sb.append("   Автор: ").append(author).append(" (").append(license).append(")\n\n");
                    }
                }
            } catch (Exception e) {
                Log.w("TvHome.Settings", "Failed to parse credits.json", e);
            }
        }
        if (sb.length() == 0) {
            File wpDir = dir == null ? null : new File(dir, "wallpapers");
            Map<String, String> captions = WallpaperCaptions.read(wpDir);
            if (!captions.isEmpty()) {
                int i = 1;
                for (Map.Entry<String, String> entry : captions.entrySet()) {
                    sb.append(i++).append(". ").append(entry.getValue()).append("\n");
                    sb.append("   Лицензия: CC BY-SA 4.0 (Wikimedia Commons)\n\n");
                }
            } else {
                sb.append("Все фоновые картины распространяются по открытой лицензии Creative Commons (CC BY-SA 4.0) из архива Wikimedia Commons.");
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Источники и лицензии обоев")
                .setMessage(sb.toString().trim())
                .setPositiveButton("Понятно", null)
                .show();
    }

    private void markChanged() {
        SharedPreferences sp = getSharedPreferences(PREF_SETTINGS, MODE_PRIVATE);
        long rev = sp.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L;
        sp.edit().putLong(Config.SETTINGS_REVISION_KEY, rev).apply();
    }

    private String getTodayKey() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            View focus = getCurrentFocus();
            if (focus != null && !sideRows.contains(focus)) {
                if (activeSection >= 0 && activeSection < sideRows.size()) {
                    lastFocusedCard = null;
                    sideRows.get(activeSection).requestFocus();
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && lastFocusedCard != null && !isDialogOpen) {
            final View restore = lastFocusedCard;
            restore.post(() -> restore.requestFocus());
        }
    }

    private interface OnToggleListener {
        void onToggle(boolean checked);
    }

    private interface ChoiceOpener {
        void open(View card, int cardIdx);
    }

    private interface OnChoiceSelected {
        void onSelected(String value, String label);
    }
}
