package com.safecall.service.user.api;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import io.swagger.v3.oas.annotations.Operation;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.user.api.UserDtos.*;
import com.safecall.service.user.service.DocumentService;

@RestController
public class DocumentController {
	private final DocumentService service;
	private final JsonMapper mapper;
	public DocumentController(DocumentService service,JsonMapper mapper) { this.service=service; this.mapper=mapper; }
	@GetMapping(value="/api/v1/documents",produces="application/json")
	@Operation(operationId="U03",summary="U03 · 동의·안내 문서 조회",tags="4. 문서·동의")
	public ResponseEntity<Items<DocumentView>> documents(@RequestParam(required=false) String codes,WebRequest request) {
		Items<DocumentView> body=service.documents(codes);
		String etag;
		try { etag="\""+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body)))+"\""; }
		catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("Document digest unavailable."); }
		var cache=CacheControl.noCache().cachePublic();
		if (request.checkNotModified(etag)) return ResponseEntity.status(304).eTag(etag).cacheControl(cache).build();
		return ResponseEntity.ok().eTag(etag).cacheControl(cache).body(body);
	}
}
