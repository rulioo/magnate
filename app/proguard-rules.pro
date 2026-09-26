# Room 生成的实现类通过反射加载，保留其构造入口。
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**
