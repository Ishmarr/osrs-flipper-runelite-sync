package com.osrsflipper.sync;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.EnumSet;

/** Local user-only files: never put bearer tokens through ConfigManager or its logs/cloud sync. */
final class PairingCredentialStore
{
    private final Path root;
    private final Gson gson = new Gson();

    PairingCredentialStore(Path root) { this.root = root; }

    PairingCredentials read(String profile) throws IOException
    {
        Path file = file(profile);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        protect(root, true);
        protect(file, false);
        if (Files.size(file) > 4096) throw new IOException("Invalid credential file size");
        try
        {
            PairingCredentials value = gson.fromJson(Files.readString(file), PairingCredentials.class);
            if (value == null || !value.isValid() || !profile.equals(value.profile))
                throw new IOException("Invalid local credential binding");
            return value;
        }
        catch (RuntimeException exception)
        {
            // JSON parser exceptions can contain the token. Never propagate their text/cause.
            throw new IOException("Unreadable local credential binding");
        }
    }

    void write(PairingCredentials credentials) throws IOException
    {
        if (credentials == null || !credentials.isValid()) throw new IOException("Invalid credential binding");
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(gson.toJson(credentials));
        if (bytes.remaining() > 4096) throw new IOException("Invalid credential file size");
        Files.createDirectories(root);
        protect(root, true);
        Path target = file(credentials.profile);
        Path temporary = Files.createTempFile(root, "pairing-", ".tmp");
        try
        {
            protect(temporary, false);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
            {
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally { Files.deleteIfExists(temporary); }
    }

    void delete(String profile) throws IOException { Files.deleteIfExists(file(profile)); }

    private Path file(String profile)
    {
        if (profile == null || !profile.matches("default|[0-9]{1,20}"))
            throw new IllegalArgumentException("Invalid RuneLite profile identity");
        return root.resolve(profile + ".json");
    }

    private static void protect(Path path, boolean directory) throws IOException
    {
        if (Files.isSymbolicLink(path)) throw new IOException("Credential paths must not be symbolic links");
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null)
        {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) throw new IOException("User-only credential permissions are unavailable");
        acl.setAcl(Collections.singletonList(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
            .setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }
}
