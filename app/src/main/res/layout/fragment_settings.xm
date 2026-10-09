<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textStyle="bold"
            android:textSize="16sp"
            android:text="Account" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:gravity="center_vertical">

            <TextView
                android:id="@+id/accountText"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:textSize="14sp"
                android:alpha="0.7"
                android:text="" />

            <Button
                android:id="@+id/signOutButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Sign out" />
        </LinearLayout>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:textStyle="bold"
            android:textSize="16sp"
            android:text="Music folder" />

        <EditText
            android:id="@+id/folderIdInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:inputType="textUri"
            android:hint="Paste your music folder's Drive link or ID" />

        <TextView
            android:id="@+id/cacheInfoText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:textSize="12sp"
            android:alpha="0.7"
            android:text="" />

        <Button
            android:id="@+id/loadLibraryButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="Load library" />

        <LinearLayout
            android:id="@+id/loadingRow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:gravity="center_vertical"
            android:visibility="gone">

            <ProgressBar
                android:id="@+id/loadingSpinner"
                android:layout_width="24dp"
                android:layout_height="24dp"
                style="?android:attr/progressBarStyleSmall" />

            <TextView
                android:id="@+id/loadingStatusText"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="12dp"
                android:text="Loading library…" />

            <Button
                android:id="@+id/cancelLoadButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Cancel" />
        </LinearLayout>

    </LinearLayout>
</ScrollView>
