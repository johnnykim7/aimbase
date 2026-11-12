package com.platform.image;

import com.platform.attachment.AttachmentService;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.domain.ChatGeneratedImageEntity;
import com.platform.domain.UsageLogEntity;
import com.platform.llm.adapter.RequestContext;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import com.platform.repository.ChatGeneratedImageRepository;
import com.platform.repository.UsageLogRepository;
import com.platform.session.CancellationRegistry;
import com.platform.session.ChatRejectedException;
import com.platform.session.SessionStore;
import com.platform.storage.StorageService;
import com.platform.tenant.TenantContext;
import com.platform.tenant.quota.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CR-184: 위젯 그림 만들기. 모델을 부르지 않고 그림 연결로 한 장을 만들어, 질문과 그림 참조 답 한 줄을
 * 한 묶음으로 대화 기록에 남긴다(BIZ-231).
 */
@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    public static final String ACTION_CREATE = "create";
    public static final String ACTION_EDIT = "edit";

    public static final String CODE_INVALID = "image_invalid";
    public static final String CODE_NOT_CONFIGURED = "image_not_configured";
    public static final String CODE_PROMPT_EMPTY = "image_prompt_empty";
    public static final String CODE_PROMPT_TOO_LONG = "image_prompt_too_long";
    public static final String CODE_TOO_MANY_SOURCES = "image_too_many_sources";
    public static final String CODE_SOURCE_INVALID = "image_source_invalid";
    public static final String CODE_NOT_FOUND = "image_not_found";

    static final String STORAGE_CATEGORY = "generated-images";
    static final String IMAGE_REF_SCHEME = "aimbase-image:";
    /** 답 글 그림 설명은 앞 100자(BIZ-231). 대화 기록을 받는 모델이 그림 내용을 알 만큼만 둔다. */
    static final int ALT_MAX_CODE_POINTS = 100;
    /** 중지된 답 표시. 대화 답 중지와 같은 글(OrchestratorEngine, ToolCallHandler). */
    static final String ABORTED_TEXT = "[중단됨]";
    /** 사용량 한도 검사에 넘기는 예상 토큰. 대화 답 검사(OrchestratorEngine)와 같은 값이다. */
    private static final int QUOTA_ESTIMATE_TOKENS = 1000;

    private static final String ROLE_USER = "user";
    private static final String TYPE_TEXT = "text";
    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_IMAGE_URL = "image_url";
    private static final String DATA_URI_PREFIX = "data:";
    private static final String MEDIA_IMAGE_PREFIX = "image/";
    private static final Set<String> ACTIONS = Set.of(ACTION_CREATE, ACTION_EDIT);

    private final ImageConnectionResolver connectionResolver;
    private final ImageGenerationSettings settings;
    private final OpenAiImageClient client;
    private final ChatGeneratedImageRepository imageRepository;
    private final AttachmentService attachmentService;
    private final StorageService storage;
    private final SessionStore sessionStore;
    private final CancellationRegistry cancellationRegistry;
    private final UsageLogRepository usageLogRepository;
    private final QuotaService quotaService;

    public ImageGenerationService(ImageConnectionResolver connectionResolver,
                                  ImageGenerationSettings settings,
                                  OpenAiImageClient client,
                                  ChatGeneratedImageRepository imageRepository,
                                  AttachmentService attachmentService,
                                  StorageService storage,
                                  SessionStore sessionStore,
                                  CancellationRegistry cancellationRegistry,
                                  UsageLogRepository usageLogRepository,
                                  QuotaService quotaService) {
        this.connectionResolver = connectionResolver;
        this.settings = settings;
        this.client = client;
        this.imageRepository = imageRepository;
        this.attachmentService = attachmentService;
        this.storage = storage;
        this.sessionStore = sessionStore;
        this.cancellationRegistry = cancellationRegistry;
        this.usageLogRepository = usageLogRepository;
        this.quotaService = quotaService;
    }

    /** 요청 메시지 한 줄. content 는 대화 요청과 같이 글 하나 또는 블록 목록이다. */
    public record RequestMessage(String role, Object content) {}

    /** 검사를 마친 그림 요청. */
    public record Prepared(String sessionId, ImageConnection connection, String prompt,
                           List<OpenAiImageClient.SourceImage> sources, List<Map<String, Object>> sourceRefs,
                           List<UnifiedMessage> requestMessages) {}

    /** 그림 응답. aborted 면 image, markdown 이 없다. */
    public record Outcome(boolean aborted, UUID turnId, Integer turnSeq, String model,
                          String markdown, ChatGeneratedImageEntity image) {}

    /** 그림과 그 바이트. */
    public record LoadedImage(ChatGeneratedImageEntity image, byte[] bytes) {}

    /**
     * 스트림을 열기 전 검사(CR-184 3.1 순서). 실패는 400/404 {@link ChatRejectedException}.
     */
    public Prepared prepare(String chatConnectionId, String sessionId, String action, String sourceImageId,
                            List<RequestMessage> messages) {
        if (action == null || !ACTIONS.contains(action)
                || (ACTION_EDIT.equals(action) && (sourceImageId == null || sourceImageId.isBlank()))) {
            throw reject(HttpStatus.BAD_REQUEST, CODE_INVALID);
        }
        ImageConnection conn = connectionResolver.resolve(chatConnectionId)
                .orElseThrow(() -> reject(HttpStatus.BAD_REQUEST, CODE_NOT_CONFIGURED));

        List<RequestMessage> msgs = messages != null ? messages : List.of();
        int lastUser = -1;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (ROLE_USER.equalsIgnoreCase(msgs.get(i).role())) { lastUser = i; break; }
        }
        List<Part> lastParts = lastUser >= 0 ? parts(msgs.get(lastUser).content()) : List.of();

        String prompt = promptOf(lastParts);
        if (prompt.isBlank()) throw reject(HttpStatus.BAD_REQUEST, CODE_PROMPT_EMPTY);
        if (prompt.codePointCount(0, prompt.length()) > settings.maxPromptChars()) {
            throw reject(HttpStatus.BAD_REQUEST, CODE_PROMPT_TOO_LONG);
        }

        boolean edit = ACTION_EDIT.equals(action);
        long attachedImages = lastParts.stream().filter(p -> p.kind == PartKind.IMAGE).count();
        if (attachedImages + (edit ? 1 : 0) > settings.maxSourceImages()) {
            throw reject(HttpStatus.BAD_REQUEST, CODE_TOO_MANY_SOURCES);
        }
        for (RequestMessage m : msgs) {
            if (parts(m.content()).stream().anyMatch(p -> p.kind == PartKind.OTHER)) {
                throw reject(HttpStatus.BAD_REQUEST, CODE_SOURCE_INVALID);
            }
        }

        List<OpenAiImageClient.SourceImage> sources = new ArrayList<>();
        List<Map<String, Object>> sourceRefs = new ArrayList<>();
        List<UnifiedMessage> requestMessages = new ArrayList<>();
        for (int i = 0; i < msgs.size(); i++) {
            List<ContentBlock> blocks = new ArrayList<>();
            for (Part p : parts(msgs.get(i).content())) {
                if (p.kind == PartKind.TEXT) {
                    blocks.add(new ContentBlock.Text(p.text));
                    continue;
                }
                ResolvedImage img = resolveImage(p.map, sessionId);
                blocks.add(ContentBlock.Image.ofBase64(img.mediaType, Base64.getEncoder().encodeToString(img.bytes)));
                if (i == lastUser) {
                    sources.add(new OpenAiImageClient.SourceImage(img.bytes, img.mediaType, img.filename));
                    sourceRefs.add(ref(ChatGeneratedImageEntity.SOURCE_ATTACHMENT, img.attachmentId));
                }
            }
            if (blocks.isEmpty()) blocks.add(new ContentBlock.Text(""));
            requestMessages.add(new UnifiedMessage(role(msgs.get(i).role()), blocks));
        }

        if (edit) {
            LoadedImage base = loadForSession(sourceImageId, sessionId);
            sources.add(0, new OpenAiImageClient.SourceImage(base.bytes(), base.image().getMediaType(),
                    base.image().getId() + extension(base.image().getMediaType())));
            sourceRefs.add(0, ref(ChatGeneratedImageEntity.SOURCE_GENERATED, base.image().getId().toString()));
        }

        quotaService.checkLLMQuota(TenantContext.getTenantId(), QUOTA_ESTIMATE_TOKENS).throwIfExceeded();
        return new Prepared(sessionId, conn, prompt.trim(), sources, sourceRefs, requestMessages);
    }

    /**
     * 공급처를 불러 그림을 만들고 대화 기록에 남긴다. 응답 중지면 「[중단됨]」 답만 남긴다.
     *
     * @throws ImageProviderException 공급처 실패. 대화 기록에는 아무것도 남기지 않는다
     */
    public Outcome generate(Prepared p) {
        ImageConnection conn = p.connection();
        AtomicBoolean token = cancellationRegistry.register(p.sessionId());
        try {
            Duration timeout = Duration.ofMillis(settings.timeoutMs());
            CompletableFuture<HttpResponse<byte[]>> call = client.request(conn, p.prompt(), p.sources(), timeout);
            cancellationRegistry.onCancel(token, () -> call.cancel(true));
            HttpResponse<byte[]> resp;
            try {
                resp = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (CancellationException e) {
                if (token.get()) return saveAborted(p);
                throw new ImageProviderException(ImageProviderException.FAILED, "cancelled");
            } catch (TimeoutException e) {
                call.cancel(true);
                throw new ImageProviderException(ImageProviderException.TIMEOUT, null);
            } catch (ExecutionException e) {
                if (token.get()) return saveAborted(p);
                if (e.getCause() instanceof HttpTimeoutException) {
                    throw new ImageProviderException(ImageProviderException.TIMEOUT, null);
                }
                throw new ImageProviderException(ImageProviderException.FAILED,
                        e.getCause() != null ? e.getCause().getClass().getSimpleName() : null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                call.cancel(true);
                throw new ImageProviderException(ImageProviderException.FAILED, "interrupted");
            }
            if (token.get()) return saveAborted(p);
            OpenAiImageClient.Result result = client.toResult(resp);
            return saveGenerated(p, result);
        } finally {
            cancellationRegistry.clear(p.sessionId(), token);
        }
    }

    /** 그 대화에서 만든, 지운 표시 없는 그림과 바이트. 아니면 404 image_not_found. */
    public LoadedImage loadForSession(String imageId, String sessionId) {
        UUID id;
        try {
            id = UUID.fromString(imageId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw reject(HttpStatus.NOT_FOUND, CODE_NOT_FOUND);
        }
        if (sessionId == null) throw reject(HttpStatus.NOT_FOUND, CODE_NOT_FOUND);
        ChatGeneratedImageEntity image = imageRepository.findByIdAndSessionIdAndDeletedAtIsNull(id, sessionId)
                .orElseThrow(() -> reject(HttpStatus.NOT_FOUND, CODE_NOT_FOUND));
        try (InputStream in = storage.load(image.getStoragePath())) {
            return new LoadedImage(image, in.readAllBytes());
        } catch (IOException | RuntimeException e) {
            log.warn("Generated image file unreadable: id={} path={} {}", id, image.getStoragePath(), e.getMessage());
            throw reject(HttpStatus.NOT_FOUND, CODE_NOT_FOUND);
        }
    }

    static String markdown(String prompt, UUID imageId) {
        return "![" + alt(prompt) + "](" + IMAGE_REF_SCHEME + imageId + ")";
    }

    /** 줄바꿈과 마크다운 그림 글을 깨는 글자(대괄호, 역빗금)를 빈칸으로 바꾼 설명 앞 100자. */
    static String alt(String prompt) {
        String head = prompt.codePointCount(0, prompt.length()) > ALT_MAX_CODE_POINTS
                ? prompt.substring(0, prompt.offsetByCodePoints(0, ALT_MAX_CODE_POINTS))
                : prompt;
        return head.replaceAll("[\\r\\n\\[\\]\\\\]", " ");
    }

    private Outcome saveAborted(Prepared p) {
        UUID turnId = UUID.randomUUID();
        List<UnifiedMessage> turn = new ArrayList<>(p.requestMessages());
        turn.add(UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, ABORTED_TEXT));
        Integer turnSeq = sessionStore.appendTurn(p.sessionId(), turn, turnId, p.connection().model(),
                RequestContext.getUserRef());
        log.info("Image generation aborted: session={} turn={}", p.sessionId(), turnId);
        return new Outcome(true, turnId, turnSeq, p.connection().model(), null, null);
    }

    private Outcome saveGenerated(Prepared p, OpenAiImageClient.Result result) {
        byte[] bytes = result.bytes();
        String mediaType = sniffMediaType(bytes);
        if (mediaType == null) {
            throw new ImageProviderException(ImageProviderException.FAILED, "not an image");
        }
        UUID imageId = UUID.randomUUID();
        String path = storage.save(TenantContext.getTenantId(), STORAGE_CATEGORY,
                imageId + extension(mediaType), new ByteArrayInputStream(bytes));
        int[] size = dimensions(bytes);

        UUID turnId = UUID.randomUUID();
        ChatGeneratedImageEntity image = new ChatGeneratedImageEntity();
        image.setId(imageId);
        image.setSessionId(p.sessionId());
        image.setTurnId(turnId);
        image.setConnectionId(p.connection().connectionId());
        image.setModel(p.connection().model());
        image.setPrompt(p.prompt());
        image.setSources(p.sourceRefs());
        image.setStoragePath(path);
        image.setMediaType(mediaType);
        image.setWidth(size != null ? size[0] : null);
        image.setHeight(size != null ? size[1] : null);
        image.setSizeBytes(bytes.length);
        imageRepository.save(image);

        String md = markdown(p.prompt(), imageId);
        List<UnifiedMessage> turn = new ArrayList<>(p.requestMessages());
        turn.add(UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, md));
        Integer turnSeq = sessionStore.appendTurn(p.sessionId(), turn, turnId, p.connection().model(),
                RequestContext.getUserRef());
        saveUsage(p, result);
        log.info("Image generated: session={} image={} model={} bytes={}", p.sessionId(), imageId,
                p.connection().model(), bytes.length);
        return new Outcome(false, turnId, turnSeq, p.connection().model(), md, image);
    }

    private void saveUsage(Prepared p, OpenAiImageClient.Result result) {
        try {
            UsageLogEntity usage = new UsageLogEntity();
            usage.setSessionId(p.sessionId());
            usage.setModel(p.connection().model());
            usage.setInputTokens(result.inputTokens());
            usage.setOutputTokens(result.outputTokens());
            usageLogRepository.save(usage);
        } catch (Exception e) {
            log.warn("Failed to save image usage log: {}", e.getMessage());
        }
    }

    private ResolvedImage resolveImage(Map<?, ?> map, String sessionId) {
        String type = (String) map.get("type");
        if (TYPE_IMAGE_URL.equals(type)) {
            Object imageUrl = map.get("image_url");
            String url = imageUrl instanceof Map<?, ?> m && m.get("url") instanceof String s ? s : null;
            return fromDataUri(url);
        }
        String attachmentId = map.get("attachment_id") instanceof String s && !s.isBlank() ? s : null;
        if (attachmentId != null) {
            UUID id;
            try {
                id = UUID.fromString(attachmentId);
            } catch (IllegalArgumentException e) {
                throw reject(HttpStatus.BAD_REQUEST, CODE_SOURCE_INVALID);
            }
            ChatAttachmentEntity att = attachmentService.loadOwned(id, sessionId);
            if (att.getMediaType() == null || !att.getMediaType().startsWith(MEDIA_IMAGE_PREFIX)) {
                throw reject(HttpStatus.BAD_REQUEST, CODE_SOURCE_INVALID);
            }
            return new ResolvedImage(attachmentService.readBytes(att), att.getMediaType(),
                    att.getId() + extension(att.getMediaType()), att.getId().toString());
        }
        String data = map.get("data") instanceof String s ? s : null;
        String mediaType = map.get("media_type") instanceof String s ? s : null;
        if (data != null && mediaType != null && mediaType.startsWith(MEDIA_IMAGE_PREFIX)) {
            return new ResolvedImage(decode(data), mediaType, "image" + extension(mediaType), null);
        }
        String url = map.get("url") instanceof String s ? s : null;
        return fromDataUri(url);
    }

    /** 공급처에 바이트로 보내야 해 data URI 만 받는다. 바깥 주소 그림은 가져오지 않는다. */
    private ResolvedImage fromDataUri(String url) {
        if (url == null || !url.startsWith(DATA_URI_PREFIX)) throw reject(HttpStatus.BAD_REQUEST, CODE_SOURCE_INVALID);
        int semicolon = url.indexOf(';');
        int comma = url.indexOf(',');
        if (semicolon < 0 || comma < semicolon) throw reject(HttpStatus.BAD_REQUEST, CODE_SOURCE_INVALID);
        String mediaType = url.substring(DATA_URI_PREFIX.length(), semicolon);
        if (!mediaType.startsWith(MEDIA_IMAGE_PREFIX)) throw reject(HttpStatus.BAD_REQUEST, CODE_SOURCE_INVALID);
        return new ResolvedImage(decode(url.substring(comma + 1)), mediaType, "image" + extension(mediaType), null);
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw reject(HttpStatus.BAD_REQUEST, CODE_SOURCE_INVALID);
        }
    }

    private static Map<String, Object> ref(String type, String id) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("type", type);
        if (id != null) ref.put("id", id);
        return ref;
    }

    private static UnifiedMessage.Role role(String role) {
        try {
            return UnifiedMessage.Role.valueOf(role.toUpperCase());
        } catch (RuntimeException e) {
            return UnifiedMessage.Role.USER;
        }
    }

    private static String promptOf(List<Part> parts) {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (p.kind != PartKind.TEXT || p.text.isBlank()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(p.text);
        }
        return sb.toString().trim();
    }

    private static List<Part> parts(Object content) {
        if (content == null) return List.of();
        if (content instanceof String s) return List.of(Part.text(s));
        if (!(content instanceof List<?> list)) return List.of(Part.text(content.toString()));
        List<Part> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> map)) continue;
            Object type = map.get("type");
            if (TYPE_TEXT.equals(type)) {
                if (map.get("text") instanceof String t) out.add(Part.text(t));
            } else if (TYPE_IMAGE.equals(type) || TYPE_IMAGE_URL.equals(type)) {
                out.add(new Part(PartKind.IMAGE, null, map));
            } else {
                out.add(new Part(PartKind.OTHER, null, map));
            }
        }
        return out;
    }

    /** 공급처가 준 바이트의 형식. 그림이 아니면 null. */
    static String sniffMediaType(byte[] b) {
        if (b == null || b.length < 12) return null;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    static String extension(String mediaType) {
        if (mediaType == null) return "";
        return switch (mediaType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> "";
        };
    }

    /** 가로, 세로. 읽지 못하면 null(설계상 비워 둘 수 있는 칸). */
    private static int[] dimensions(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static ChatRejectedException reject(HttpStatus status, String code) {
        return new ChatRejectedException(status, code);
    }

    private enum PartKind { TEXT, IMAGE, OTHER }

    private record Part(PartKind kind, String text, Map<?, ?> map) {
        static Part text(String t) {
            return new Part(PartKind.TEXT, t, null);
        }
    }

    private record ResolvedImage(byte[] bytes, String mediaType, String filename, String attachmentId) {}
}
