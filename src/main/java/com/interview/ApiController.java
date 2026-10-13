package com.interview;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;

@RestController
class ApiController {
    private final AgentService agent; private final Contracts contracts; private final ObjectMapper json;
    ApiController(AgentService agent, Contracts contracts, ObjectMapper json) { this.agent=agent; this.contracts=contracts; this.json=json; }
    @GetMapping("/healthz") Map<String,String> health() { return Map.of("status","ok"); }
    @GetMapping("/api/csrf") Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken(),"headerName",token.getHeaderName(),"parameterName",token.getParameterName()); }
    @GetMapping("/api/status") Map<String,Object> status() { return Map.of("configured",agent.configured(),"model",agent.model()); }
    @PostMapping(value="/api/agent", consumes="application/json") Map<String,Object> run(HttpServletRequest request) throws IOException {
        byte[] bytes=request.getInputStream().readNBytes(220001);
        if(bytes.length>220000) throw new AppError("输入内容过长。",413);
        com.fasterxml.jackson.databind.JsonNode raw;
        try { raw=json.readTree(bytes); } catch(Exception e) { throw new AppError("请求不是有效 JSON。"); }
        return Map.of("result",agent.run(contracts.validate(raw)));
    }
    @ExceptionHandler(AppError.class) ResponseEntity<?> error(AppError error) { return ResponseEntity.status(error.status).body(Map.of("error",error.getMessage())); }
}
