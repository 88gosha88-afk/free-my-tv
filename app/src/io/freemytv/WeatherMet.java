package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The weather from MET Norway (api.met.no, no key), for when Open-Meteo can't be reached (some networks block it). Its
 * answer is turned into the shape of Open-Meteo's, so the rest of the app reads both the same way. MET gives no
 * sunrise and sunset: they are worked out from the place and the date.
 */
final class WeatherMet {
    private WeatherMet() {}

    /** MET asks for a user agent that says who is calling. */
    static final String UA = "FreeMyTV/1.0 github.com/simbasjungles/free-my-tv";

    static String url(double lat, double lon) {
        return String.format(Locale.US, "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", lat, lon);
    }

    /** MET's answer as Open-Meteo's JSON (current, daily and hourly, in the TV's time zone). */
    static String toOpenMeteo(String body, double lat, double lon) throws Exception {
        JSONArray series = new JSONObject(body).getJSONObject("properties").getJSONArray("timeseries");
        TimeZone zone = TimeZone.getDefault();
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat local = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        local.setTimeZone(zone);
        long now = System.currentTimeMillis();
        long[] sun = sun(lat, lon, now, zone);
        long rise = sun[0], set = sun[1];
        String today = local.format(now).substring(0, 10);

        double max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY, curTemp = Double.NaN;
        int curCode = -1;
        boolean curDay = true;
        JSONArray times = new JSONArray(), temps = new JSONArray(), codes = new JSONArray(), days = new JSONArray();
        for (int i = 0; i < series.length(); i++) {
            JSONObject entry = series.getJSONObject(i);
            long at = utc.parse(entry.getString("time")).getTime();
            JSONObject data = entry.getJSONObject("data");
            double temp = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");
            int code = wmo(symbol(data));
            boolean day = isDay(at, rise, set);
            if (local.format(at).startsWith(today)) {
                max = Math.max(max, temp);
                min = Math.min(min, temp);
            }
            if (at <= now || Double.isNaN(curTemp)) {
                curTemp = temp;
                curCode = code;
                curDay = day;
            } else if (times.length() < 12) {
                times.put(local.format(at));
                temps.put(temp);
                codes.put(code);
                days.put(day ? 1 : 0);
            }
        }
        if (Double.isNaN(curTemp)) throw new Exception("MET Norway: no data");
        if (max == Double.NEGATIVE_INFINITY) {
            max = curTemp;
            min = curTemp;
        }
        JSONObject out = new JSONObject();
        out.put("timezone", zone.getID());
        out.put("current", new JSONObject().put("temperature_2m", curTemp).put("weather_code", curCode)
                .put("is_day", curDay ? 1 : 0));
        out.put("daily", new JSONObject()
                .put("temperature_2m_max", new JSONArray().put(max)).put("temperature_2m_min", new JSONArray().put(min))
                .put("sunrise", new JSONArray().put(local.format(rise))).put("sunset", new JSONArray().put(local.format(set))));
        out.put("hourly", new JSONObject().put("time", times).put("temperature_2m", temps).put("weather_code", codes)
                .put("is_day", days));
        return out.toString();
    }

    /** The symbol for the next hour (else the next 6 or 12 hours) of one entry, like "partlycloudy_day". */
    private static String symbol(JSONObject data) {
        for (String key : new String[] {"next_1_hours", "next_6_hours", "next_12_hours"}) {
            JSONObject next = data.optJSONObject(key);
            if (next == null) continue;
            JSONObject summary = next.optJSONObject("summary");
            if (summary != null && summary.has("symbol_code")) return summary.optString("symbol_code", "");
        }
        return "";
    }

    private static boolean isDay(long at, long rise, long set) {
        long dayMs = 24 * 3600_000L;
        return ((at - rise) % dayMs + dayMs) % dayMs < set - rise;
    }

    /** MET's symbol names as the WMO codes the app knows. */
    static int wmo(String symbol) {
        if (symbol == null || symbol.isEmpty()) return -1;
        String s = symbol.replaceAll("_(day|night|polartwilight)$", "");
        if (s.contains("thunder")) return s.startsWith("heavy") ? 99 : 95;
        switch (s) {
            case "clearsky": return 0;
            case "fair": return 1;
            case "partlycloudy": return 2;
            case "cloudy": return 3;
            case "fog": return 45;
            case "lightrain": case "lightsleet": return 61;
            case "rain": case "sleet": return 63;
            case "heavyrain": case "heavysleet": return 65;
            case "lightrainshowers": case "lightsleetshowers": return 80;
            case "rainshowers": case "sleetshowers": return 81;
            case "heavyrainshowers": case "heavysleetshowers": return 82;
            case "lightsnow": return 71;
            case "snow": return 73;
            case "heavysnow": return 75;
            case "lightsnowshowers": case "snowshowers": return 85;
            case "heavysnowshowers": return 86;
            default: return 3;
        }
    }

    /**
     * Today's sunrise and sunset in epoch ms for a place (NOAA's formulas); 6:00 and 18:00 local where the sun doesn't
     * rise or set.
     */
    static long[] sun(double lat, double lon, long when, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(when);
        int y = c.get(Calendar.YEAR), m = c.get(Calendar.MONTH), d = c.get(Calendar.DAY_OF_MONTH);
        double g = 2 * Math.PI / 365 * (c.get(Calendar.DAY_OF_YEAR) - 1);
        double eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double latR = Math.toRadians(lat);
        double cosH = Math.cos(Math.toRadians(90.833)) / (Math.cos(latR) * Math.cos(decl)) - Math.tan(latR) * Math.tan(decl);
        Calendar local = Calendar.getInstance(zone);
        local.clear();
        local.set(y, m, d, 0, 0, 0);
        if (cosH > 1 || cosH < -1) return new long[] {local.getTimeInMillis() + 6 * 3600_000L, local.getTimeInMillis() + 18 * 3600_000L};
        double ha = Math.toDegrees(Math.acos(cosH));
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(y, m, d, 0, 0, 0);
        long base = utc.getTimeInMillis();
        return new long[] {base + (long) ((720 - 4 * (lon + ha) - eq) * 60_000), base + (long) ((720 - 4 * (lon - ha) - eq) * 60_000)};
    }
}
