package dev.comfybatch.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
@SecurityScheme(
        name = "ApiKey",
        type = SecuritySchemeType.APIKEY,
        in = io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.HEADER,
        paramName = "X-API-Key")
@OpenAPIDefinition(security = @SecurityRequirement(name = "ApiKey"))
public class ApiKeyFilter extends OncePerRequestFilter {
    private final byte[] key;
    private final HandlerExceptionResolver resolver;

    public ApiKeyFilter(
            AppProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        if (properties.apiKey().isBlank())
            throw new IllegalStateException("Set API_KEY in .env before starting");
        this.key = properties.apiKey().getBytes(StandardCharsets.UTF_8);
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/")) {
            String supplied = request.getHeader("X-API-Key");
            if (supplied == null
                    || !MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8))) {
                resolver.resolveException(
                        request,
                        response,
                        null,
                        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
