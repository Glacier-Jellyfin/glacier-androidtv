# Project-specific R8 rules. Libraries ship their own consumer rules; add
# entries here only for code reached through reflection or known-unused paths.

# kotlin-logging (used by the Jellyfin SDK) references SLF4J, which is not
# bundled: GlacierApplication routes logging to Logcat, so the SLF4J path is
# never taken.
-dontwarn org.slf4j.**
