package iselm1.docs;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 학습 문서 API. 접근 규칙은 SecurityConfig 의 기본 규칙(이메일 인증을 마친 회원·관리자)을 따른다.
 * <ul>
 *   <li>GET /api/docs/{section}/{topic} → 문서 목록 (파일 이름 순)</li>
 *   <li>GET /api/docs/{section}/{topic}/{slug} → 문서 하나 (Markdown 원문)</li>
 * </ul>
 * 경로의 각 부분은 소문자·숫자·하이픈만 허용하고, 실제 파일 위치가 문서 폴더 안인지 다시 확인한다.
 */
@RestController
@RequestMapping("/api/docs")
public class DocsController {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final String EXT = ".md";

    public record DocSummary(String slug, String title) {}

    public record Doc(String slug, String title, String markdown) {}

    private final Path root;

    public DocsController(DocsProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize();
    }

    @GetMapping("/{section}/{topic}")
    public List<DocSummary> list(@PathVariable String section, @PathVariable String topic) {
        Path dir = resolve(section, topic);
        if (dir == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!Files.isDirectory(dir)) return List.of(); // 아직 문서가 없는 항목
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(this::isDocFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(p -> {
                        String slug = slugOf(p);
                        return new DocSummary(slug, titleOf(read(p), slug));
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/{section}/{topic}/{slug}")
    public Doc get(@PathVariable String section, @PathVariable String topic, @PathVariable String slug) {
        Path file = resolve(section, topic, slug + EXT);
        if (file == null || !isDocFile(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String markdown = read(file);
        return new Doc(slug, titleOf(markdown, slug), markdown);
    }

    /** 이름 형식이 맞고 문서 폴더 안에 있을 때만 경로를 돌려준다. 아니면 null. */
    private Path resolve(String... parts) {
        Path path = root;
        for (int i = 0; i < parts.length; i++) {
            String name = i == parts.length - 1 && parts[i].endsWith(EXT)
                    ? parts[i].substring(0, parts[i].length() - EXT.length()) : parts[i];
            if (!NAME.matcher(name).matches()) return null;
            path = path.resolve(parts[i]);
        }
        path = path.normalize();
        return path.startsWith(root) ? path : null;
    }

    /** 일반 .md 파일이고, 심볼릭 링크를 따라가도 문서 폴더 안인지 확인한다. */
    private boolean isDocFile(Path file) {
        if (!Files.isRegularFile(file) || !file.getFileName().toString().endsWith(EXT)) return false;
        if (!NAME.matcher(slugOf(file)).matches()) return false;
        try {
            return file.toRealPath().startsWith(root.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static String slugOf(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - EXT.length());
    }

    /** 첫 번째 "# 제목" 줄. 없으면 slug. */
    private static String titleOf(String markdown, String fallback) {
        return markdown.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).strip())
                .filter(title -> !title.isEmpty())
                .findFirst()
                .orElse(fallback);
    }

    private static String read(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return text.startsWith("﻿") ? text.substring(1) : text; // 메모장 등의 BOM 제거
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
