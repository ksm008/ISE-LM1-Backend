package iselm1.docs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * 학습 문서(Markdown) 설정.
 * 문서는 jar 밖의 폴더에서 요청마다 읽으므로, 파일을 고치면 서버를 재시작하지 않아도 반영된다.
 */
@ConfigurationProperties(prefix = "docs")
public record DocsProperties(
        /** 문서 폴더. 구조: {root}/{section}/{topic}/{slug}.md (상대 경로는 서버 실행 위치 기준) */
        @DefaultValue("./docs") Path root
) {}
