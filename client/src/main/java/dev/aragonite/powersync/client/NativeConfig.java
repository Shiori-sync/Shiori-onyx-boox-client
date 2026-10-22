package dev.aragonite.powersync.client;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.TreeMap;
import org.json.JSONException;

/** A factory-reset device has no persisted system settings until the first write. */
final class NativeConfig {
 static String canonical(Object v)throws JSONException {if(v instanceof JSONObject o){TreeMap<String,String> keys=new TreeMap<>();Iterator<String> i=o.keys();while(i.hasNext()){String k=i.next();keys.put(k,canonical(o.get(k)));}return keys.toString();}if(v instanceof JSONArray a){ArrayList<String> r=new ArrayList<>();for(int i=0;i<a.length();i++)r.add(canonical(a.get(i)));return r.toString();}return new JSONArray().put(v).toString();}
 static JSONObject parse(String key, String raw) throws JSONException {
  if ("ONYX_SYSTEM_CONFIG_KEY".equals(key) && raw.isEmpty())
   return new JSONObject().put("configMap", new JSONObject());
  JSONObject config = new JSONObject(raw);
  if ("ONYX_SYSTEM_CONFIG_KEY".equals(key)) {
   if (!config.has("configMap")) config.put("configMap", new JSONObject());
   else config.getJSONObject("configMap"); // Reject malformed existing settings.
  }
  return config;
 }
 static String flag(JSONObject map, String key) throws JSONException {
  if (!map.has(key)) return "false";
  Object value = map.get(key);
  if (!(value instanceof String) || !(value.equals("true") || value.equals("false")))
   throw new JSONException("Invalid native sync flag");
  return (String)value;
 }
}
