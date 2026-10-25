package ar.chanchi.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interpreta el texto de las tarjetas y las fichas de cada portal. */
final class Parse {
    private Parse() {}

    static final Object[][] BARRIOS = {
        {"Palermo", -34.5880, -58.4260}, {"Belgrano", -34.5630, -58.4540}, {"Recoleta", -34.5880, -58.3930},
        {"Puerto Madero", -34.6120, -58.3630}, {"Retiro", -34.5930, -58.3760}, {"Caballito", -34.6190, -58.4400},
        {"Villa Crespo", -34.5990, -58.4380}, {"Colegiales", -34.5750, -58.4490}, {"Almagro", -34.6080, -58.4190},
        {"Núñez", -34.5460, -58.4600}, {"Chacarita", -34.5880, -58.4540}, {"Villa Urquiza", -34.5720, -58.4880},
        {"Coghlan", -34.5600, -58.4750}, {"Villa Ortúzar", -34.5850, -58.4700}, {"San Telmo", -34.6210, -58.3720},
        {"Monserrat", -34.6120, -58.3800}, {"Boedo", -34.6290, -58.4170}, {"Villa del Parque", -34.6040, -58.4900},
        {"Villa Devoto", -34.6000, -58.5130}, {"Saavedra", -34.5560, -58.4850}, {"Villa Pueyrredón", -34.5780, -58.5050},
        {"Agronomía", -34.5950, -58.4930}, {"San Cristóbal", -34.6220, -58.4000}, {"Paternal", -34.5970, -58.4690},
        {"Monte Castro", -34.6150, -58.5150}, {"Villa Luro", -34.6330, -58.5000}, {"Vélez Sarsfield", -34.6300, -58.4850},
        {"Flores", -34.6330, -58.4640}, {"Balvanera", -34.6090, -58.4040}, {"Parque Patricios", -34.6360, -58.4000},
        {"Barracas", -34.6420, -58.3730}, {"Liniers", -34.6460, -58.5200}, {"Constitución", -34.6270, -58.3830},
        {"La Boca", -34.6340, -58.3630}, {"Mataderos", -34.6600, -58.5050}, {"Nueva Pompeya", -34.6500, -58.4150},
        {"Villa Lugano", -34.6760, -58.4700}, {"Villa Soldati", -34.6700, -58.4400}, {"San Nicolás", -34.6020, -58.3850},
        {"Parque Chas", -34.5850, -58.4780}, {"Floresta", -34.6280, -58.4800}, {"Versalles", -34.6320, -58.5200},
    };
    // alias -> barrio canónico
    static final String[][] ALIAS = {
        {"palermo soho", "Palermo"}, {"palermo hollywood", "Palermo"}, {"palermo chico", "Palermo"}, {"palermo viejo", "Palermo"},
        {"belgrano r", "Belgrano"}, {"belgrano c", "Belgrano"}, {"barrio norte", "Recoleta"}, {"once", "Balvanera"},
        {"abasto", "Balvanera"}, {"congreso", "Balvanera"}, {"tribunales", "San Nicolás"}, {"microcentro", "San Nicolás"},
        {"centro", "San Nicolás"}, {"las canitas", "Palermo"}, {"villa gral. mitre", "Villa del Parque"},
    };

    static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    static String barrio(String loc, String all) {
        for (String src : new String[]{loc, all}) {
            String t = " " + norm(src).replaceAll("[^a-z0-9. ]", " ") + " ";
            for (String[] a : ALIAS) if (t.contains(" " + a[0] + " ")) return a[1];
            String best = null; int bl = 0;
            for (Object[] b : BARRIOS) {
                String nb = norm((String) b[0]);
                if (t.contains(" " + nb + " ") && nb.length() > bl) { best = (String) b[0]; bl = nb.length(); }
            }
            if (best != null) return best;
        }
        return null;
    }

    static double[] centroid(String barrio) {
        for (Object[] b : BARRIOS) if (b[0].equals(barrio)) return new double[]{(Double) b[1], (Double) b[2]};
        return new double[]{-34.6037, -58.4100};
    }

    static boolean inCaba(double lat, double lng) {
        return lat < -34.52 && lat > -34.71 && lng < -58.33 && lng > -58.54;
    }

    static long num(String s) {
        if (s == null) return 0;
        String d = s.replaceAll("[^0-9]", "");
        if (d.isEmpty() || d.length() > 12) return 0;
        return Long.parseLong(d);
    }

    private static final Pattern PRICE = Pattern.compile("(USD|U\\$S|US\\$|u\\$s|\\$)\\s*([0-9][0-9.,]*)");
    private static final Pattern EXP1 = Pattern.compile("\\+?\\s*\\$\\s*([0-9][0-9.,]*)\\s*(?:de\\s+)?[Ee]xpensas");
    private static final Pattern EXP2 = Pattern.compile("[Ee]xpensas[^0-9$]{0,12}\\$\\s*([0-9][0-9.,]*)");
    private static final Pattern M2 = Pattern.compile("(\\d{2,4}(?:[.,]\\d+)?)\\s*m(?:²|2)");
    private static final Pattern AMB = Pattern.compile("(\\d{1,2})\\s*(?:amb\\b|amb\\.|ambientes?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DORM = Pattern.compile("(\\d{1,2})\\s*(?:dorm\\b|dorm\\.|dormitorios?|habitaci)", Pattern.CASE_INSENSITIVE);

    /** Completa precio, expensas, m², ambientes y dormitorios a partir de texto. No pisa lo que ya tiene. */
    static void fill(JSONObject ad, String text) throws Exception {
        if (text == null) return;
        if (!ad.has("rent")) {
            Matcher m = PRICE.matcher(text);
            while (m.find()) {
                long v = num(m.group(2));
                String sym = m.group(1);
                boolean usd = !sym.equals("$");
                if (v < (usd ? 100 : 50000)) continue; // descarta "$ 1" sueltos
                ad.put("cur", usd ? "USD" : "ARS");
                ad.put("rent", v);
                break;
            }
        }
        if (!ad.has("exp")) {
            Matcher m = EXP1.matcher(text);
            if (!m.find()) { m = EXP2.matcher(text); if (!m.find()) m = null; }
            if (m != null) { long v = num(m.group(1)); if (v > 0) ad.put("exp", v); }
        }
        if (!ad.has("m2")) {
            Matcher m = M2.matcher(text);
            if (m.find()) ad.put("m2", Math.round(Double.parseDouble(m.group(1).replace(',', '.'))));
        }
        if (!ad.has("amb")) {
            Matcher m = AMB.matcher(text);
            if (m.find()) ad.put("amb", Integer.parseInt(m.group(1)));
            else if (norm(text).contains("monoambiente")) ad.put("amb", 1);
        }
        if (!ad.has("dorm")) {
            Matcher m = DORM.matcher(text);
            if (m.find()) ad.put("dorm", Integer.parseInt(m.group(1)));
            else if (norm(text).contains("monoambiente")) ad.put("dorm", 0);
        }
    }

    /** Lo que solo se ve bien en la ficha completa. */
    static void features(JSONObject ad, String text) throws Exception {
        String t = norm(text);
        ad.put("balcon", t.contains("balcon"));
        boolean sep = Pattern.compile("cocina (separada|independiente|cerrada|definida|con puerta)|cocina y (comedor|living) (separad|independ)").matcher(t).find();
        boolean integ = Pattern.compile("cocina (integrada|americana|abierta)|kitchenette|cocina y (comedor|living) integrad|cocina-comedor|cocina comedor").matcher(t).find();
        if (sep) ad.put("cocina", true); else if (integ) ad.put("cocina", false); else ad.remove("cocina");
        boolean owner = Pattern.compile("dueno directo|alquila (el )?dueno|dueno alquila|directo con (el )?dueno|sin comision|sin inmobiliaria|propietario directo|anunciante particular").matcher(t).find();
        ad.put("owner", owner);
        JSONArray g = new JSONArray();
        if (t.contains("caucion")) g.put("caucion");
        if (Pattern.compile("garantia propietaria|garantia propia|garantia de propietario|garantia con propietario|aval propietario|propietaria").matcher(t).find()) g.put("propietaria");
        ad.put("guar", g);
    }

    static String signature(JSONObject o) {
        long r = o.optLong("rent");
        boolean usd = "USD".equals(o.optString("cur"));
        return o.optString("barrio") + "|" + o.optInt("amb") + "|" + (o.optLong("m2") / 6) + "|" + (usd ? "U" : "A") + "|" + (r / (usd ? 40 : 40000));
    }

    // ---------- coincidencia con las alertas ----------
    static boolean matches(JSONObject s, JSONObject ad, double rate) {
        try {
            String zone = norm(s.optString("zone", ""));
            if (!zone.isEmpty()) {
                String first = zone.split("[,/]")[0].trim();
                String b = norm(ad.optString("barrio"));
                boolean generic = first.isEmpty() || first.equals("capital federal") || first.equals("caba") || first.equals("buenos aires");
                if (!generic && !(b.contains(first) || first.contains(b))) return false;
            }
            String d = s.optString("dorm", "any");
            int dorm = ad.optInt("dorm");
            if (d.equals("1") && dorm != 1) return false;
            if (d.equals("2") && dorm != 2) return false;
            if (d.equals("3") && dorm < 3) return false;
            if (s.optBoolean("balcon") && !ad.optBoolean("balcon")) return false;
            if (s.optBoolean("cocina") && ad.has("cocina") && !ad.optBoolean("cocina")) return false;
            if (s.optBoolean("owner") && !ad.optBoolean("owner")) return false;
            JSONArray g = ad.optJSONArray("guar");
            String gs = g == null ? "" : g.toString();
            if (s.optBoolean("caucion") && !gs.contains("caucion")) return false;
            if (s.optBoolean("propietaria") && !gs.contains("propietaria")) return false;
            long bud = num(s.optString("budget", ""));
            if (bud > 0) {
                double budArs = "USD".equals(s.optString("cur")) ? bud * rate : bud;
                double total = ad.optLong("rent") * ("USD".equals(ad.optString("cur")) ? rate : 1) + ad.optLong("exp", 0);
                if (total > budArs) return false;
            }
            return true;
        } catch (Exception e) { return false; }
    }
}
