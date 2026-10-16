package androidx.datastore.preferences.core
abstract class Preferences { class Key<T>(val name: String) }
fun stringPreferencesKey(name: String): Preferences.Key<String> = Preferences.Key(name)
