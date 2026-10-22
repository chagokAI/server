package com.skala.chagok.dlanalysis;

import com.skala.chagok.global.error.BusinessException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import static org.assertj.core.api.Assertions.*;

class CaptureImageStoreTest {
    @TempDir Path root;

    static MockMultipartFile photo(int w, int h, String name) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("images", name, "image/png", bytes.toByteArray());
    }

    @Test void preservesOriginalBytesAndCameraOrderInWarehouseDirectory() throws Exception {
        UUID warehouse = UUID.randomUUID();
        List<MultipartFile> files = List.of(photo(1920,1080,"../../cam3.png"),
                photo(1920,1080,"cam1.png"), photo(1920,1080,"cam2.png"));
        var store = new CaptureImageStore(root.toString());
        var refs = store.save(warehouse, files);
        assertThat(refs).hasSize(3);
        for (int i=0; i<3; i++) {
            var path = Path.of(refs.get(i));
            assertThat(path.startsWith(root.resolve(warehouse.toString()))).isTrue();
            assertThat(path.getFileName().toString()).isEqualTo("cam"+(i+1)+".png");
            assertThat(Files.readAllBytes(path)).isEqualTo(files.get(i).getBytes());
        }
        store.discard(refs);
        assertThat(Files.exists(Path.of(refs.getFirst()).getParent())).isFalse();
    }

    @Test void rejectsMissingCameraBeforeWritingFiles() throws Exception {
        assertThatThrownBy(() -> new CaptureImageStore(root.toString()).save(UUID.randomUUID(),
                List.of(photo(1920,1080,"cam1.png")))).isInstanceOf(BusinessException.class);
        try (var files = Files.list(root)) { assertThat(files.count()).isZero(); }
    }

    @Test void rejectsResizedImagesBeforeWritingAnyFiles() throws Exception {
        var valid = photo(1920,1080,"cam1.png");
        var small = photo(960,540,"cam2.png");
        assertThatThrownBy(() -> new CaptureImageStore(root.toString()).save(UUID.randomUUID(),
                List.of(valid,small,valid))).isInstanceOf(BusinessException.class);
        try (var files = Files.list(root)) { assertThat(files.count()).isZero(); }
    }

    @Test void rejectsNonImageRegardlessOfExtension() {
        var invalid = new MockMultipartFile("images","cam1.jpg","image/jpeg","not an image".getBytes());
        assertThatThrownBy(() -> new CaptureImageStore(root.toString()).save(UUID.randomUUID(),
                List.of(invalid,invalid,invalid))).isInstanceOf(BusinessException.class);
    }
}
