package com.skala.chagok.dlanalysis;

import com.skala.chagok.global.error.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** 서버와 DL Worker가 공유하는 디렉터리에 cam1~3 순서로 원본을 저장한다. */
@Component
public class CaptureImageStore {
    private final Path root;
    public CaptureImageStore(@Value("${chagok.dl.image-root:./data/captures}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public List<String> save(UUID warehouseId, List<MultipartFile> images) {
        if (images == null || images.size() != 3)
            throw BusinessException.invalidField("images", "cam1~3 순서로 이미지 3장이 필요합니다.");
        // 전체 파일을 검증한 다음 저장해 일부만 유효한 촬영이 발행되지 않게 한다.
        List<String> formats = images.stream().map(this::format).toList();
        Path dir = root.resolve(warehouseId.toString()).resolve(UUID.randomUUID().toString());
        List<String> refs = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            for (int i = 0; i < images.size(); i++) {
                Path file = dir.resolve("cam" + (i + 1) + "." + formats.get(i));
                try (InputStream in = images.get(i).getInputStream()) { Files.copy(in, file); }
                refs.add(file.toString());
            }
            return List.copyOf(refs);
        } catch (IOException e) {
            deleteDirectory(dir);
            throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE, "촬영 이미지를 저장하지 못했습니다.");
        }
    }

    private String format(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 12 * 1024 * 1024)
            throw BusinessException.invalidField("images", "이미지 한 장은 12MB 이하의 JPEG 또는 PNG여야 합니다.");
        try (InputStream in = file.getInputStream(); var stream = ImageIO.createImageInputStream(in)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("지원하지 않는 이미지");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!List.of("jpeg", "jpg", "png").contains(format)) throw new IOException("지원하지 않는 이미지");
                if (reader.getWidth(0) != 1920 || reader.getHeight(0) != 1080)
                    throw BusinessException.invalidField("images", "이미지는 축소하지 않은 1920×1080 원본이어야 합니다.");
                return format.equals("png") ? "png" : "jpg";
            } finally { reader.dispose(); }
        } catch (IOException e) {
            throw BusinessException.invalidField("images", "JPEG 또는 PNG 이미지를 읽을 수 없습니다.");
        }
    }

    public void discard(List<String> refs) {
        if (!refs.isEmpty()) deleteDirectory(Path.of(refs.getFirst()).getParent());
    }

    private void deleteDirectory(Path dir) {
        if (!dir.startsWith(root) || dir.equals(root)) return;
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) { /* 원래 실패 사유를 유지한다. */ }
    }
}
