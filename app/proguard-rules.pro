# ORCA Player release rules. Libraries ship their own consumer rules (kotlinx.serialization, Room, Hilt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# sshj (SFTP source) has an optional GSS-API/Kerberos auth method that references JDK-only JAAS classes.
# Android has no JAAS; that auth method is never used, so silence the reference.
-dontwarn javax.security.auth.login.**
