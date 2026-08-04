# Firestore documents are mapped by hand, so no model classes need to survive
# obfuscation. These rules only quiet warnings from transitive dependencies.
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
