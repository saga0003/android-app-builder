package com.saga.calculator;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.MathContext;

public class MainActivity extends Activity {

    private TextView expressionView;
    private TextView displayView;

    private String input = "0";
    private BigDecimal firstValue = null;
    private String pendingOperator = null;
    private boolean startNewInput = false;
    private boolean errorState = false;

    private final MathContext mathContext = MathContext.DECIMAL64;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(245, 246, 248));
        window.setNavigationBarColor(Color.rgb(245, 246, 248));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(18));
        root.setBackgroundColor(Color.rgb(245, 246, 248));

        TextView title = new TextView(this);
        title.setText("Calculator");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(35, 39, 47));
        title.setPadding(dp(4), 0, 0, dp(16));
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout displayCard = new LinearLayout(this);
        displayCard.setOrientation(LinearLayout.VERTICAL);
        displayCard.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        displayCard.setPadding(dp(18), dp(20), dp(18), dp(18));
        displayCard.setBackground(rounded(Color.WHITE, 24));

        expressionView = new TextView(this);
        expressionView.setText("");
        expressionView.setTextSize(16);
        expressionView.setTextColor(Color.rgb(135, 140, 150));
        expressionView.setGravity(Gravity.END);
        displayCard.addView(expressionView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        displayView = new TextView(this);
        displayView.setText("0");
        displayView.setTextSize(44);
        displayView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        displayView.setTextColor(Color.rgb(28, 31, 38));
        displayView.setGravity(Gravity.END);
        displayView.setSingleLine(true);
        displayView.setPadding(0, dp(8), 0, 0);
        displayCard.addView(displayView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(150));
        cardParams.setMargins(0, 0, 0, dp(18));
        root.addView(displayCard, cardParams);

        LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        keypad.setGravity(Gravity.BOTTOM);

        addRow(keypad, new String[]{"C", "±", "%", "÷"});
        addRow(keypad, new String[]{"7", "8", "9", "×"});
        addRow(keypad, new String[]{"4", "5", "6", "−"});
        addRow(keypad, new String[]{"1", "2", "3", "+"});
        addRow(keypad, new String[]{"⌫", "0", ".", "="});

        root.addView(keypad, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        setContentView(root);
    }

    private void addRow(LinearLayout parent, String[] labels) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        for (String label : labels) {
            Button button = makeButton(label);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(72), 1f);
            params.setMargins(dp(5), dp(5), dp(5), dp(5));
            row.addView(button, params);
        }

        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(22);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);

        boolean operator = label.equals("÷") || label.equals("×") || label.equals("−") || label.equals("+") || label.equals("=");
        boolean utility = label.equals("C") || label.equals("±") || label.equals("%") || label.equals("⌫");

        if (operator) {
            button.setTextColor(Color.WHITE);
            button.setBackground(rounded(Color.rgb(55, 92, 246), 22));
        } else if (utility) {
            button.setTextColor(Color.rgb(55, 92, 246));
            button.setBackground(rounded(Color.rgb(229, 234, 255), 22));
        } else {
            button.setTextColor(Color.rgb(35, 39, 47));
            button.setBackground(rounded(Color.WHITE, 22));
        }

        button.setOnClickListener(v -> handleButton(label));
        return button;
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
        expressionView.setText(format(firstValue) + " " + operator);
        startNewInput = true;
        refreshDisplay();
    }

    private void equalsPressed() {
        if (firstValue == null || pendingOperator == null || errorState) return;

        BigDecimal secondValue = parseInput();
        String expression = format(firstValue) + " " + pendingOperator + " " + format(secondValue) + " =";
        BigDecimal result = apply(firstValue, secondValue, pendingOperator);
        if (result == null) return;

        input = format(result);
        expressionView.setText(expression);
        firstValue = null;
        pendingOperator = null;
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
                        showError();
                        return null;
                    }
                    return left.divide(right, mathContext);
                default:
                    return right;
            }
        } catch (ArithmeticException ex) {
            showError();
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
        expressionView.setText("");
        refreshDisplay();
    }

    private void showError() {
        errorState = true;
        input = "0";
        firstValue = null;
        pendingOperator = null;
        startNewInput = true;
        expressionView.setText("Cannot divide by zero");
        displayView.setText("Error");
    }

    private void recoverFromError() {
        if (errorState) {
            clearAll();
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

    private void refreshDisplay() {
        if (!errorState) {
            displayView.setText(input);
        }
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
