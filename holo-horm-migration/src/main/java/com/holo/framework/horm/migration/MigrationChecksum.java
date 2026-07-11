package com.holo.framework.horm.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;

/**
 * 迁移脚本校验和工具。
 *
 * <p>使用 CRC32 算法计算迁移脚本的校验和（与 Flyway 默认行为一致），
 * 用于检测已应用的迁移脚本是否被修改。
 */
public final class MigrationChecksum {

    private MigrationChecksum() {
    }

    /**
     * 计算字节数组的校验和。
     *
     * @param content 内容
     * @return 校验和
     */
    public static int compute(byte[] content) {
        CRC32 crc = new CRC32();
        crc.update(content);
        return (int) crc.getValue();
    }

    /**
     * 计算输入流的校验和。
     *
     * @param input 输入流
     * @return 校验和
     * @throws IOException 如果读取失败
     */
    public static int compute(InputStream input) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = input.read(buffer)) != -1) {
            crc.update(buffer, 0, len);
        }
        return (int) crc.getValue();
    }

    /**
     * 计算文件的校验和。
     *
     * @param path 文件路径
     * @return 校验和
     * @throws IOException 如果读取失败
     */
    public static int compute(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return compute(is);
        }
    }
}
