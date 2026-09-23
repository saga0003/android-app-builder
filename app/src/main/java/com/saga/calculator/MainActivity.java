package com.saga.calculator;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashMap;
import java.util.Map;

/**
 * Calculator — "Obsidian" UI.
 *
 * Fully programmatic, dependency-free UI: custom circular keys with ripple,
 * press-scale and haptic feedback, gradient operator/equals keys, active
 * operator highlighting, auto-shrinking display and thousands separators.
 */
public class MainActivity extends Activity {

    // ---------------------------------------------------------------------
    // Palette (Obsidian + Electric Blue)
    // ---------------------------------------------------------------------
    private static final int BG_TOP = 0xFF171C27;
    private static final int BG_BOTTOM = 0xFF0A0C11;

    private static final int HEADER_TEXT = 0xFF5F6B80;
    private static final int EXPR_TEXT = 0xFF7A86A0;
    private static final int DISPLAY_TEXT = 0xFFF5F7FC;

    private static final int[] NUMBER_FILL = {0xFF242D40, 0xFF171D2B};
    private static final int NUMBER_TEXT = 0xFFF5F7FC;

    private static final int[] UTILITY_FILL = {0xFF2D3549, 0xFF212838};
    private static final int UTILITY_TEXT = 0xFF9DAAD0;

    private static final int[] OPERATOR_FILL = {0xFF3352E0, 0xFF4E71FA};
    private static final int OPERATOR_TEXT = 0xFFFFFFFF;

    private static final int[] EQUALS_FILL = {0xFF2F4DF6, 0xFF6E90FF};

    private static final int[] ACTIVE_FILL = {0xFFE9EEFF, 0xFFD7E0FA};
    private static final int ACTIVE_TEXT = 0xFF2F4DF6;

    private static final int RIPPLE_COLOR = 0x2EFFFFFF;
    private static final int DOT_TOP = 0xFF5C7CFF;
    private static final int DOT_BOTTOM = 0xFF2E4BFF;

    private static final String[] ROW_1 = {"C", "±", "%", "÷"};
    private static final String[] ROW_2 = {"7", "8", "9", "×"};
    private static final String[] ROW_3 = {"4", "5", "6", "−"};
    private static final String[] ROW_4 = {"1", "2", "3", "+"};
    private static final String[] ROW_5 = {"⌫", "0", ".", "="};

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------
    private TextView expressionView;
    private TextView displayView;

    private String input = "0";
    private BigDecimal firstValue = null;
    private String pendingOperator = null;
    private boolean startNewInput = false;
    private boolean errorState = false;

    private final Map<String, KeyButton> operatorKeys = new HashMap<>();
    private final MathContext mathContext = MathContext.DECIMAL64;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupEdgeToEdge();

        // ---- Key sizing: uniform gaps, circles sized from real screen width
        int margin = dp(6);
        DisplayMetricsHolder dm = displayMetrics();
        int cellWidth = (dm.widthPx - dp(40) - 8 * margin) / 4;
        int keySize = clamp(cellWidth, dp(48), dp(74));
        int heightCap = (int) (dm.heightPx * 0.44f) / 5 - margin;
        keySize = clamp(keySize, dp(44), Math.max(dp(44), heightCap));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(verticalGradient(BG_TOP, BG_BOTTOM));
        root.setPadding(dp(20), dp(10), dp(20), dp(12));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(dp(20), top + dp(10), dp(20), bottom + dp(12));
            return insets.consumeSystemWindowInsets();
        });

        // ---- Header
        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- Display block (floats to the bottom of its flexible area)
        LinearLayout displayArea = new LinearLayout(this);
        displayArea.setOrientation(LinearLayout.VERTICAL);
        displayArea.setGravity(Gravity.BOTTOM);
        displayArea.setPadding(0, 0, 0, dp(18));

        expressionView = new TextView(this);
        expressionView.setText("");
        expressionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        expressionView.setTypeface(Typeface.create("sans-serif-regular", Typeface.NORMAL));
        expressionView.setTextColor(EXPR_TEXT);
        expressionView.setGravity(Gravity.END);
        expressionView.setSingleLine(true);
        expressionView.setMaxLines(1);
        expressionView.setEllipsize(TextUtils.TruncateAt.START);
        expressionView.setPadding(0, 0, dp(2), dp(6));
        displayArea.addView(expressionView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        displayView = new TextView(this);
        displayView.setText("0");
        displayView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        displayView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        displayView.setTextColor(DISPLAY_TEXT);
        displayView.setGravity(Gravity.END);
        displayView.setSingleLine(true);
        displayView.setMaxLines(1);
        displayView.setPadding(0, 0, dp(2), 0);
        displayArea.addView(displayView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(displayArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- Keypad
        LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);

        String[][] rows = {ROW_1, ROW_2, ROW_3, ROW_4, ROW_5};
        for (String[] labels : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (String label : labels) {
                KeyButton key = makeKey(label);
                LinearLayout.LayoutParams params =
                        new LinearLayout.LayoutParams(0, keySize, 1f);
                params.setMargins(margin, margin, margin, margin);
                row.addView(key, params);
            }
            keypad.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        root.addView(keypad, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    // ---------------------------------------------------------------------
    // Chrome / system bars
    // ---------------------------------------------------------------------
    private void setupEdgeToEdge() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }

    private LinearLayout buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = new TextView(this);
        brand.setText("CALCULATOR");
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        brand.setLetterSpacing(0.22f);
        brand.setTextColor(HEADER_TEXT);
        header.addView(brand, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        View dot = new View(this);
        dot.setBackground(roundDot(DOT_TOP, DOT_BOTTOM));
        header.addView(dot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        return header;
    }

    // ---------------------------------------------------------------------
    // Keys
    // ---------------------------------------------------------------------
    private KeyButton makeKey(String label) {
        KeyButton key;
        boolean isOperator = "÷".equals(label) || "×".equals(label)
                || "−".equals(label) || "+".equals(label);

        if ("=".equals(label)) {
            key = new KeyButton(label, EQUALS_FILL, OPERATOR_TEXT,
                    0.42f, ACTIVE_FILL, ACTIVE_TEXT);
        } else if (isOperator) {
            key = new KeyButton(label, OPERATOR_FILL, OPERATOR_TEXT,
                    0.38f, ACTIVE_FILL, ACTIVE_TEXT);
            operatorKeys.put(label, key);
        } else if ("C".equals(label) || "±".equals(label)
                || "%".equals(label) || "⌫".equals(label)) {
            key = new KeyButton(label, UTILITY_FILL, UTILITY_TEXT,
                    0.30f, ACTIVE_FILL, ACTIVE_TEXT);
        } else {
            key = new KeyButton(label, NUMBER_FILL, NUMBER_TEXT,
                    0.34f, ACTIVE_FILL, ACTIVE_TEXT);
        }

        key.setOnClickListener(v -> handleButton(label));
        if ("⌫".equals(label)) {
            key.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                clearAll();
                return true;
            });
        }
        return key;
    }

    private void handleButton(String label) {
        if (label.matches("[0-9]")) {
            enterDigit(label);
            return;
        }
        switch (label) {
            case ".":
                enterDecimal();
                break;
            case "C":
                clearAll();
                break;
            case "⌫":
                backspace();
                break;
            case "±":
                toggleSign();
                break;
            case "%":
                percentage();
                break;
            case "÷":
            case "×":
            case "−":
            case "+":
                chooseOperator(label);
                break;
            case "=":
                equalsPressed();
                break;
        }
    }

    // ---------------------------------------------------------------------
    // Calculator logic
    // ---------------------------------------------------------------------
    private void enterDigit(String digit) {
        recoverFromError();
        if (startNewInput || input.equals("0")) {
            input = digit;
            startNewInput = false;
        } else if (input.length() < 15) {
            input += digit;
        }
        refreshDisplay();
    }

    private void enterDecimal() {
        recoverFromError();
        if (startNewInput) {
            input = "0.";
            startNewInput = false;
        } else if (!input.contains(".")) {
            input += ".";
        }
        refreshDisplay();
    }

    private void chooseOperator(String operator) {
        recoverFromError();
        BigDecimal current = parseInput();

        if (firstValue != null && pendingOperator != null && !startNewInput) {
            BigDecimal result = apply(firstValue, current, pendingOperator);
            if (result == null) return;
            firstValue = result;
            input = format(result);
        } else {
            firstValue = current;
        }

        pendingOperator = operator;
        setOperatorHighlight(operator);
        expressionView.setText(formatForDisplay(format(firstValue)) + " " + operator);
        startNewInput = true;
        refreshDisplay();
    }

    private void equalsPressed() {
        if (firstValue == null || pendingOperator == null || errorState) return;

        BigDecimal secondValue = parseInput();
        String expression = formatForDisplay(format(firstValue)) + " "
                + pendingOperator + " "
                + formatForDisplay(format(secondValue)) + " =";
        BigDecimal result = apply(firstValue, secondValue, pendingOperator);
        if (result == null) return;

        input = format(result);
        expressionView.setText(expression);
        firstValue = null;
        pendingOperator = null;
        setOperatorHighlight(null);
        startNewInput = true;
        refreshDisplay();
    }

    private BigDecimal apply(BigDecimal left, BigDecimal right, String operator) {
        try {
            switch (operator) {
                case "+":
                    return left.add(right, mathContext);
                case "−":
                    return left.subtract(right, mathContext);
                case "×":
                    return left.multiply(right, mathContext);
                case "÷":
                    if (right.compareTo(BigDecimal.ZERO) == 0) {
                        showError("Cannot divide by zero");
                        return null;
                    }
                    return left.divide(right, mathContext);
                default:
                    return right;
            }
        } catch (ArithmeticException ex) {
            showError("Math error");
            return null;
        }
    }

    private void toggleSign() {
        recoverFromError();
        if (input.equals("0")) return;
        input = input.startsWith("-") ? input.substring(1) : "-" + input;
        refreshDisplay();
    }

    private void percentage() {
        recoverFromError();
        BigDecimal value = parseInput().divide(new BigDecimal("100"), mathContext);
        input = format(value);
        refreshDisplay();
    }

    private void backspace() {
        recoverFromError();
        if (startNewInput) return;
        if (input.length() <= 1 || (input.startsWith("-") && input.length() == 2)) {
            input = "0";
        } else {
            input = input.substring(0, input.length() - 1);
        }
        refreshDisplay();
    }

    private void clearAll() {
        input = "0";
        firstValue = null;
        pendingOperator = null;
        startNewInput = false;
        errorState = false;
        setOperatorHighlight(null);
        expressionView.setText("");
        refreshDisplay();
    }

    private void showError(String message) {
        errorState = true;
        input = "0";
        firstValue = null;
        pendingOperator = null;
        startNewInput = true;
        setOperatorHighlight(null);
        expressionView.setText(message);
        displayView.setText("Error");
        fitDisplayText();
    }

    private void recoverFromError() {
        if (errorState) {
            clearAll();
        }
    }

    private void setOperatorHighlight(String operator) {
        for (Map.Entry<String, KeyButton> entry : operatorKeys.entrySet()) {
            entry.getValue().setHighlighted(entry.getKey().equals(operator));
        }
    }

    private BigDecimal parseInput() {
        try {
            return new BigDecimal(input);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private String format(BigDecimal number) {
        if (number == null) return "0";
        BigDecimal normalized = number.stripTrailingZeros();
        if (normalized.compareTo(BigDecimal.ZERO) == 0) return "0";
        return normalized.toPlainString();
    }

    /** Groups the integer part of a raw numeric string with thousands separators. */
    private String formatForDisplay(String raw) {
        if (raw == null || raw.isEmpty()) return "0";
        boolean negative = raw.startsWith("-");
        String body = negative ? raw.substring(1) : raw;

        String intPart = body;
        String fracPart = null;
        int dot = body.indexOf('.');
        if (dot >= 0) {
            intPart = body.substring(0, dot);
            fracPart = body.substring(dot);
        }

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = intPart.length() - 1; i >= 0; i--) {
            sb.append(intPart.charAt(i));
            count++;
            if (count % 3 == 0 && i > 0) sb.append(',');
        }
        String out = sb.reverse().toString() + (fracPart != null ? fracPart : "");
        return negative ? "-" + out : out;
    }

    private void refreshDisplay() {
        if (errorState) return;
        String shown = formatForDisplay(input);
        displayView.setText(shown);
        fitDisplayText();
    }

    /** Shrinks the display font as the number grows so it always fits on one line. */
    private void fitDisplayText() {
        int len = displayView.getText().length();
        float sizeSp;
        if (len <= 9) {
            sizeSp = 56;
        } else if (len <= 12) {
            sizeSp = 44;
        } else if (len <= 16) {
            sizeSp = 34;
        } else {
            sizeSp = 26;
        }
        displayView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
    }

    // ---------------------------------------------------------------------
    // Drawing helpers
    // ---------------------------------------------------------------------
    private android.graphics.drawable.GradientDrawable verticalGradient(int top, int bottom) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom});
        return drawable;
    }

    private android.graphics.drawable.GradientDrawable roundDot(int top, int bottom) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom});
        drawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    /** Tiny holder so display metrics are read once. */
    private static final class DisplayMetricsHolder {
        final int widthPx;
        final int heightPx;

        DisplayMetricsHolder(int widthPx, int heightPx) {
            this.widthPx = widthPx;
            this.heightPx = heightPx;
        }
    }

    private DisplayMetricsHolder displayMetrics() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        return new DisplayMetricsHolder(metrics.widthPixels, metrics.heightPixels);
    }

    // ---------------------------------------------------------------------
    // KeyButton — circular key with ripple, press-scale and haptics
    // ---------------------------------------------------------------------
    private final class KeyButton extends View {

        private final String label;
        private final int[] fillColors;
        private final int[] activeFillColors;
        private final int textColor;
        private final int activeTextColor;
        private final float textSizeFactor;

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RippleDrawable ripple;
        private final RectF circle = new RectF();

        private boolean highlighted = false;

        KeyButton(String label, int[] fillColors, int textColor, float textSizeFactor,
                  int[] activeFillColors, int activeTextColor) {
            super(MainActivity.this);
            this.label = label;
            this.fillColors = fillColors;
            this.textColor = textColor;
            this.textSizeFactor = textSizeFactor;
            this.activeFillColors = activeFillColors;
            this.activeTextColor = activeTextColor;

            textPaint.setColor(textColor);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

            GradientMask mask = new GradientMask();
            ripple = new RippleDrawable(ColorStateList.valueOf(RIPPLE_COLOR), null, mask);

            setClickable(true);
            setFocusable(true);
            setContentDescription(label);
            applyFill();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            int side = Math.min(w, h);
            float cx = w / 2f;
            float cy = h / 2f;
            circle.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);
            ripple.setBounds((int) circle.left, (int) circle.top,
                    (int) circle.right, (int) circle.bottom);
            textPaint.setTextSize(side * textSizeFactor);
            applyFill();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawOval(circle, fillPaint);
            ripple.draw(canvas);
            float baseline = circle.centerY()
                    - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(label, circle.centerX(), baseline, textPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    ripple.setHotspot(event.getX(), event.getY());
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    animate().scaleX(0.93f).scaleY(0.93f).setDuration(70).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    break;
                default:
                    break;
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            ripple.setState(getDrawableState());
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            ripple.setCallback(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            ripple.setCallback(null);
            super.onDetachedFromWindow();
        }

        void setHighlighted(boolean on) {
            if (highlighted == on) return;
            highlighted = on;
            applyFill();
            invalidate();
        }

        private void applyFill() {
            int[] colors = highlighted ? activeFillColors : fillColors;
            if (colors.length == 2) {
                fillPaint.setShader(new LinearGradient(
                        circle.left, circle.top, circle.right, circle.bottom,
                        colors[0], colors[1], Shader.TileMode.CLAMP));
            } else {
                fillPaint.setShader(null);
                fillPaint.setColor(colors[0]);
            }
            textPaint.setColor(highlighted ? activeTextColor : textColor);
        }

        /** Oval ripple mask that always matches the drawn circle bounds. */
        private final class GradientMask extends android.graphics.drawable.GradientDrawable {
            GradientMask() {
                super();
                setShape(OVAL);
                setColor(0xFFFFFFFF);
            }
        }
    }
}
