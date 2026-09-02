package com.kaffe.menuadmin.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ProductImageDerivativeServiceTest {

    @Test
    void generatesAProgressiveJpegWithinTheThumbnailBounds() throws IOException {
        BufferedImage source = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        try {
            graphics.setPaint(new java.awt.GradientPaint(0, 0, Color.BLACK, 1200, 800, Color.ORANGE));
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream encodedSource = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encodedSource);

        ProductImageDerivativeService.Thumbnail thumbnail =
                ProductImageDerivativeService.createThumbnail(encodedSource.toByteArray());

        assertThat(thumbnail).isNotNull();
        assertThat(thumbnail.width()).isEqualTo(384);
        assertThat(thumbnail.height()).isEqualTo(256);
        assertThat(thumbnail.bytes()).startsWith((byte) 0xff, (byte) 0xd8);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail.bytes()));
        assertThat(decoded.getWidth()).isEqualTo(384);
        assertThat(decoded.getHeight()).isEqualTo(256);
    }

    @Test
    void neverUpscalesSmallImages() throws IOException {
        BufferedImage source = new BufferedImage(180, 120, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encodedSource = new ByteArrayOutputStream();
        ImageIO.write(source, "jpg", encodedSource);

        ProductImageDerivativeService.Thumbnail thumbnail =
                ProductImageDerivativeService.createThumbnail(encodedSource.toByteArray());

        assertThat(thumbnail).isNotNull();
        assertThat(thumbnail.width()).isEqualTo(180);
        assertThat(thumbnail.height()).isEqualTo(120);
    }

    @Test
    void buildsStableSiblingKeysAndUrls() {
        assertThat(ProductImageDerivativeService.thumbnailKey(
                "media/tenants/19/menu/products/85/asset.jpg"
        )).isEqualTo("media/tenants/19/menu/products/85/asset-thumb.jpg");
        assertThat(ProductImageDerivativeService.thumbnailUrl(
                "https://cdn.kaffe.test/media/tenants/19/menu/products/85/asset.jpg"
        )).isEqualTo("https://cdn.kaffe.test/media/tenants/19/menu/products/85/asset-thumb.jpg");
    }
}
