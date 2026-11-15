package com.estudotron.viewmodel;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formatação de números e horários para a interface (pt-BR). */
public final class Formats {

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", PT_BR);

    private Formats() {}

    public static String tokens(int count) {
        return count >= 1000 ? String.format(PT_BR, "%.1f mil", count / 1000.0) : String.valueOf(count);
    }

    /** "≈ US$ 0,0123" (custo estimado pelo catálogo de preços). */
    public static String usd(double value) {
        NumberFormat format = NumberFormat.getNumberInstance(PT_BR);
        format.setMinimumFractionDigits(value < 0.1 ? 4 : 2);
        format.setMaximumFractionDigits(value < 0.1 ? 4 : 2);
        return "≈ US$ " + format.format(value);
    }

    public static String time(Instant instant) {
        return TIME.format(instant.atZone(ZoneId.systemDefault()));
    }
}
