package dev.comfybatch;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
@Component
@SecurityScheme(name="ApiKey",type=SecuritySchemeType.APIKEY,in=io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.HEADER,paramName="X-API-Key")
@OpenAPIDefinition(security=@SecurityRequirement(name="ApiKey"))
public class Security extends OncePerRequestFilter {
 final byte[] key;
 public Security(@Value("${app.api-key}")String key){if(key.isBlank())throw new IllegalStateException("Set API_KEY in .env before starting");this.key=key.getBytes(StandardCharsets.UTF_8);}
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
  if(req.getRequestURI().startsWith("/api/")){
   String supplied=req.getHeader("X-API-Key");if(supplied==null||!MessageDigest.isEqual(key,supplied.getBytes(StandardCharsets.UTF_8))){res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"error\":\"Unauthorized\"}");return;}
  }chain.doFilter(req,res);
 }
}
