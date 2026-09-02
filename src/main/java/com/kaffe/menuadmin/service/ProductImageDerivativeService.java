package com.kaffe.menuadmin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaffe.common.media.MediaAsset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
class ProductImageDerivativeService {

    static final String IMMUTABLE_CACHE_CONTROL = "public,max-age=31536000,immutable";
    static final int THUMBNAIL_MAX_EDGE = 384;
    private static final float THUMBNAIL_JPEG_QUALITY = 0.78f;

    private final S3Client s3Client;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    void prepareForDelivery(MediaAsset asset) {
        ResponseBytes<GetObjectResponse> sourceObject = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(asset.bucket())
                .key(asset.objectKey())
                .build());

        Thumbnail thumbnail = createThumbnail(sourceObject.asByteArray());
        applyImmutableCache(asset, sourceObject.response());
        if (thumbnail == null) {
            return;
        }

        String thumbnailKey = thumbnailKey(asset.objectKey());
        String thumbnailUrl = thumbnailUrl(asset.publicUrl());
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(asset.bucket())
                        .key(thumbnailKey)
                        .contentType("image/jpeg")
                        .contentLength((long) thumbnail.bytes().length)
                        .cacheControl(IMMUTABLE_CACHE_CONTROL)
                        .metadata(Map.of(
                                "source-asset-id", asset.assetId().toString(),
                                "variant", "thumbnail"
                        ))
                        .build(),
                RequestBody.fromBytes(thumbnail.bytes())
        );

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("url", thumbnailUrl);
        descriptor.put("objectKey", thumbnailKey);
        descriptor.put("contentType", "image/jpeg");
        descriptor.put("width", thumbnail.width());
        descriptor.put("height", thumbnail.height());
        descriptor.put("sizeBytes", thumbnail.bytes().length);

        int updated = jdbcTemplate.update("""
                        update media.assets
                        set metadata = jsonb_set(
                                jsonb_set(
                                    coalesce(metadata, '{}'::jsonb),
                                    '{variants}',
                                    case
                                        when jsonb_typeof(metadata -> 'variants') = 'object'
                                            then metadata -> 'variants'
                                        else '{}'::jsonb
                                    end,
                                    true
                                ),
                                '{variants,thumbnail}',
                                ?::jsonb,
                                true
                            ),
                            updated_at = now()
                        where asset_id = ?
                          and tenant_id = ?
                        """,
                toJson(descriptor),
                asset.assetId(),
                asset.tenantId()
        );
        if (updated != 1) {
            throw new IllegalStateException("Product image asset metadata was not updated");
        }
    }

    private void applyImmutableCache(MediaAsset asset, GetObjectResponse source) {
        if (IMMUTABLE_CACHE_CONTROL.equals(source.cacheControl())) {
            return;
        }
        CopyObjectRequest.Builder request = CopyObjectRequest.builder()
                .sourceBucket(asset.bucket())
                .sourceKey(asset.objectKey())
                .destinationBucket(asset.bucket())
                .destinationKey(asset.objectKey())
                .metadataDirective(MetadataDirective.REPLACE)
                .metadata(source.metadata())
                .contentType(source.contentType() == null ? asset.contentType() : source.contentType())
                .cacheControl(IMMUTABLE_CACHE_CONTROL);
        if (source.contentDisposition() != null) {
            request.contentDisposition(source.contentDisposition());
        }
        if (source.contentEncoding() != null) {
            request.contentEncoding(source.contentEncoding());
        }
        if (source.contentLanguage() != null) {
            request.contentLanguage(source.contentLanguage());
        }
        s3Client.copyObject(request.build());
    }

    static Thumbnail createThumbnail(byte[] sourceBytes) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(sourceBytes));
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return null;
            }

            double scale = Math.min(1d, (double) THUMBNAIL_MAX_EDGE
                    / Math.max(source.getWidth(), source.getHeight()));
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = resized.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            return new Thumbnail(encodeJpeg(resized), width, height);
        } catch (IOException exception) {
            throw new IllegalStateException("Product image thumbnail could not be generated", exception);
        }
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("JPEG image writer is not available");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(THUMBNAIL_JPEG_QUALITY);
            }
            if (parameters.canWriteProgressive()) {
                parameters.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
            output.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    static String thumbnailKey(String objectKey) {
        return replaceExtension(objectKey, "-thumb.jpg");
    }

    static String thumbnailUrl(String publicUrl) {
        return replaceExtension(publicUrl, "-thumb.jpg");
    }

    private static String replaceExtension(String value, String suffix) {
        int slash = value.lastIndexOf('/');
        int dot = value.lastIndexOf('.');
        return (dot > slash ? value.substring(0, dot) : value) + suffix;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Product image metadata could not be serialized", exception);
        }
    }

    record Thumbnail(byte[] bytes, int width, int height) {
    }
}
