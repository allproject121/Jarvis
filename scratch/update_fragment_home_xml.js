const fs = require('fs');
let xml = fs.readFileSync('app/src/main/res/layout/fragment_home.xml', 'utf8');

// 1. Add btnToggleZenMode into layoutHeader before layoutTopRightActions
if (!xml.includes('id="@+id/btnToggleZenMode"')) {
  const targetHeader = '<LinearLayout\n                    android:id="@+id/layoutTopRightActions"';
  const newButton = `<ImageButton
                    android:id="@+id/btnToggleZenMode"
                    android:layout_width="38dp"
                    android:layout_height="38dp"
                    android:layout_alignParentEnd="true"
                    android:layout_centerVertical="true"
                    android:background="@drawable/bg_jarvis_icon_square"
                    android:src="@drawable/ic_zen_mode"
                    android:padding="8dp"
                    android:scaleType="fitCenter"
                    app:tint="?attr/themeTextPrimary"
                    android:contentDescription="Toggle Immersion Mode" />

                <LinearLayout
                    android:id="@+id/layoutTopRightActions"`;
  xml = xml.replace(targetHeader, newButton);
  console.log('Added btnToggleZenMode to layoutHeader');
}

// 2. Add btnExitZenMode floating pill before </androidx.constraintlayout.widget.ConstraintLayout>
if (!xml.includes('id="@+id/btnExitZenMode"')) {
  const exitPill = `
    <!-- Floating Exit Zen Mode / Restore HUD Pill Button -->
    <LinearLayout
        android:id="@+id/btnExitZenMode"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="24dp"
        android:background="@drawable/bg_jarvis_status_badge"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingHorizontal="18dp"
        android:paddingVertical="9dp"
        android:clickable="true"
        android:focusable="true"
        android:elevation="12dp"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <ImageView
            android:id="@+id/ivExitZenIcon"
            android:layout_width="16dp"
            android:layout_height="16dp"
            android:src="@drawable/ic_zen_mode"
            app:tint="#00F0FF"
            android:contentDescription="Restore HUD" />

        <TextView
            android:id="@+id/tvExitZenText"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:text="RESTORE HUD"
            android:textColor="#FFFFFF"
            android:textSize="12sp"
            android:textStyle="bold"
            android:letterSpacing="0.08" />
    </LinearLayout>
</androidx.constraintlayout.widget.ConstraintLayout>`;
  xml = xml.replace('</androidx.constraintlayout.widget.ConstraintLayout>', exitPill);
  console.log('Added btnExitZenMode to fragment_home.xml');
}

fs.writeFileSync('app/src/main/res/layout/fragment_home.xml', xml, 'utf8');
console.log('Successfully updated fragment_home.xml');
