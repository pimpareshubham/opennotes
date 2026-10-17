package org.opennotes.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import org.opennotes.app.R;

public class StarColorPickerDialog {

    public interface OnColorSelectedListener {
        void onColorSelected(int color);
    }

    public static class PaletteGroup {
        public final String name;
        public final int[] colors;

        public PaletteGroup(String name, int[] colors) {
            this.name = name;
            this.colors = colors;
        }
    }

    public static final PaletteGroup[] STAR_PALETTES = new PaletteGroup[]{
            new PaletteGroup("Classic Notes", new int[]{
                    0xFF111827, 0xFF475569, 0xFF1D4ED8, 0xFF007355, 0xFFB33333, 0xFFDAA001, 0xFFFEF08A
            }),
            new PaletteGroup("Morandi Elegance", new int[]{
                    0xFFC2847A, 0xFF9FA8A3, 0xFF848C8E, 0xFFD7C49E, 0xFF343148, 0xFF6B7280
            }),
            new PaletteGroup("Dopamine Vibrant", new int[]{
                    0xFFFF5757, 0xFF007BFF, 0xFF28A745, 0xFF9C27B0, 0xFFFD7E14, 0xFFE83E8C
            }),
            new PaletteGroup("Highlighter Glow", new int[]{
                    0xFFFEFF00, 0xFF00FF66, 0xFF00E5FF, 0xFFFF007F, 0xFFFF9100, 0xFFB388FF
            }),
            new PaletteGroup("Dark Contrast", new int[]{
                    0xFFFFFFFF, 0xFFA7F3D0, 0xFFBAE6FD, 0xFFFED7AA, 0xFFE9D5FF, 0xFFFDE68A
            }),
            new PaletteGroup("Vintage & Earthy", new int[]{
                    0xFF3E2723, 0xFF795548, 0xFFA1887F, 0xFFD7CCC8, 0xFFBF360C, 0xFF4E342E
            })
    };

    public static void show(Context context, String title, int initialColor, OnColorSelectedListener listener) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_star_color_picker, null);

        TextView textTitle = view.findViewById(R.id.textColorPickerTitle);
        if (title != null) textTitle.setText(title);

        View previewSwatch = view.findViewById(R.id.viewSelectedColorPreview);
        TabLayout tabLayout = view.findViewById(R.id.colorPickerTabLayout);
        LinearLayout containerPaletteTab = view.findViewById(R.id.containerPaletteTab);
        ScrollView containerColorSetTab = view.findViewById(R.id.containerColorSetTab);
        LinearLayout paletteGroupsLayout = view.findViewById(R.id.paletteGroupsLayout);

        SatValPickerView satValPickerView = view.findViewById(R.id.satValPickerView);
        HueSliderView hueSliderView = view.findViewById(R.id.hueSliderView);
        ImageView btnEyedropper = view.findViewById(R.id.btnEyedropper);
        EditText editHexColor = view.findViewById(R.id.editHexColor);
        SeekBar seekOpacity = view.findViewById(R.id.seekOpacity);
        TextView textOpacityPercent = view.findViewById(R.id.textOpacityPercent);
        LinearLayout layoutQuickSpectrumSwatches = view.findViewById(R.id.layoutQuickSpectrumSwatches);

        int baseColor = (initialColor != 0) ? initialColor : 0xFF38BDF8;
        final float[] hsv = new float[3];
        Color.colorToHSV(baseColor, hsv);
        final int[] alphaVal = {Color.alpha(baseColor)};
        final int[] currentColor = {baseColor};

        final boolean[] isUpdatingHexText = {false};

        Runnable updateUi = () -> {
            int baseRgb = Color.HSVToColor(hsv);
            int argb = Color.argb(alphaVal[0], Color.red(baseRgb), Color.green(baseRgb), Color.blue(baseRgb));
            currentColor[0] = argb;

            // Swatch
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(argb);
            gd.setStroke(3, 0xFFCBD5E1);
            previewSwatch.setBackground(gd);

            // Hex text
            if (!isUpdatingHexText[0]) {
                isUpdatingHexText[0] = true;
                editHexColor.setText(String.format("#%06X", (0xFFFFFF & argb)));
                isUpdatingHexText[0] = false;
            }

            // Opacity text
            int percent = (int) Math.round((alphaVal[0] / 255.0) * 100);
            textOpacityPercent.setText(percent + "%");
        };

        // Initialize pickers
        satValPickerView.setHue(hsv[0]);
        satValPickerView.setSatVal(hsv[1], hsv[2]);
        hueSliderView.setHue(hsv[0]);
        int initPercent = (int) Math.round((alphaVal[0] / 255.0) * 100);
        seekOpacity.setProgress(initPercent);
        updateUi.run();

        satValPickerView.setOnColorChangeListener((hue, sat, val) -> {
            hsv[0] = hue;
            hsv[1] = sat;
            hsv[2] = val;
            updateUi.run();
        });

        hueSliderView.setOnHueChangeListener(hue -> {
            hsv[0] = hue;
            satValPickerView.setHue(hue);
            updateUi.run();
        });

        seekOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    alphaVal[0] = (int) Math.round((progress / 100.0) * 255);
                    updateUi.run();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        editHexColor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (isUpdatingHexText[0]) return;
                String hex = s.toString().trim();
                if (!hex.startsWith("#")) hex = "#" + hex;
                if (hex.length() == 7) {
                    try {
                        int parsed = Color.parseColor(hex);
                        Color.colorToHSV(parsed, hsv);
                        satValPickerView.setHue(hsv[0]);
                        satValPickerView.setSatVal(hsv[1], hsv[2]);
                        hueSliderView.setHue(hsv[0]);
                        isUpdatingHexText[0] = true;
                        updateUi.run();
                        isUpdatingHexText[0] = false;
                    } catch (Exception ignored) {}
                }
            }
        });

        btnEyedropper.setOnClickListener(v -> {
            Toast.makeText(context, "Eyedropper active: tap anywhere on note canvas", Toast.LENGTH_SHORT).show();
        });

        // Tabs
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() == 0) {
                    containerPaletteTab.setVisibility(View.VISIBLE);
                    containerColorSetTab.setVisibility(View.GONE);
                } else {
                    containerPaletteTab.setVisibility(View.GONE);
                    containerColorSetTab.setVisibility(View.VISIBLE);
                }
            }

            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setView(view)
                .setPositiveButton("Select", (d, w) -> {
                    if (listener != null) listener.onColorSelected(currentColor[0]);
                })
                .setNegativeButton("Cancel", null)
                .create();

        org.opennotes.app.util.ThemeUtils.styleDialog(dialog, context);

        // Quick Swatches in Palette Tab
        int[] quicks = {
                0xFF111827, 0xFFFFFFFF, 0xFF38BDF8, 0xFF3B82F6, 0xFF10B981,
                0xFFF59E0B, 0xFFEF4444, 0xFFA855F7, 0xFFEC4899, 0xFFF97316
        };
        for (int qCol : quicks) {
            View qCircle = new View(context);
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(64, 64);
            qlp.setMargins(6, 0, 6, 0);
            qCircle.setLayoutParams(qlp);

            GradientDrawable qgd = new GradientDrawable();
            qgd.setShape(GradientDrawable.OVAL);
            qgd.setColor(qCol);
            qgd.setStroke(2, 0xFF475569);
            qCircle.setBackground(qgd);

            qCircle.setOnClickListener(v -> {
                Color.colorToHSV(qCol, hsv);
                alphaVal[0] = Color.alpha(qCol);
                satValPickerView.setHue(hsv[0]);
                satValPickerView.setSatVal(hsv[1], hsv[2]);
                hueSliderView.setHue(hsv[0]);
                int p = (int) Math.round((alphaVal[0] / 255.0) * 100);
                seekOpacity.setProgress(p);
                updateUi.run();
            });
            layoutQuickSpectrumSwatches.addView(qCircle);
        }

        // Color Set Tab content
        for (PaletteGroup group : STAR_PALETTES) {
            TextView groupTitle = new TextView(context);
            groupTitle.setText(group.name);
            groupTitle.setTextSize(13f);
            groupTitle.setTextColor(0xFF94A3B8);
            groupTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            groupTitle.setPadding(4, 10, 4, 6);
            paletteGroupsLayout.addView(groupTitle);

            LinearLayout swatchesRow = new LinearLayout(context);
            swatchesRow.setOrientation(LinearLayout.HORIZONTAL);
            swatchesRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            swatchesRow.setPadding(0, 0, 0, 10);

            for (int col : group.colors) {
                FrameLayout swatchFrame = new FrameLayout(context);
                LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(92, 92);
                flp.setMargins(4, 0, 4, 0);
                swatchFrame.setLayoutParams(flp);

                View circle = new View(context);
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(68, 68);
                clp.gravity = android.view.Gravity.CENTER;
                circle.setLayoutParams(clp);

                GradientDrawable gd = new GradientDrawable();
                gd.setShape(GradientDrawable.OVAL);
                gd.setColor(col);
                if (col == currentColor[0]) {
                    gd.setStroke(6, 0xFF38BDF8);
                } else {
                    gd.setStroke(2, 0xFF475569);
                }
                circle.setBackground(gd);

                swatchFrame.addView(circle);
                swatchFrame.setOnClickListener(v -> {
                    currentColor[0] = col;
                    Color.colorToHSV(col, hsv);
                    alphaVal[0] = Color.alpha(col);
                    satValPickerView.setHue(hsv[0]);
                    satValPickerView.setSatVal(hsv[1], hsv[2]);
                    hueSliderView.setHue(hsv[0]);
                    updateUi.run();
                    if (listener != null) {
                        listener.onColorSelected(col);
                    }
                    dialog.dismiss();
                });

                swatchesRow.addView(swatchFrame);
            }
            paletteGroupsLayout.addView(swatchesRow);
        }

        dialog.show();
    }
}
