package dev.comfybatch;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
final class Json {
 static final ObjectMapper M=new ObjectMapper();
 static String write(Object o){try{return M.writeValueAsString(o);}catch(Exception e){throw new IllegalArgumentException("Invalid JSON",e);}}
 static JsonNode read(String s){try{return M.readTree(s);}catch(Exception e){throw new IllegalArgumentException("Invalid JSON",e);}}
 static String hash(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);}}
 static JsonNode sorted(JsonNode n){if(n.isObject()){var result=M.createObjectNode();var names=new TreeSet<String>();n.fieldNames().forEachRemaining(names::add);names.forEach(k->result.set(k,sorted(n.get(k))));return result;}if(n.isArray()){var result=M.createArrayNode();n.forEach(x->result.add(sorted(x)));return result;}return n;}
 static String canonical(JsonNode n){return sorted(n).toString();}
 static String hashRequest(Object o){return hash(canonical(M.valueToTree(o)).getBytes(StandardCharsets.UTF_8));}
}
