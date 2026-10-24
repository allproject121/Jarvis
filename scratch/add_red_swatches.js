const fs = require('fs');

// 1. activity_customization_studio.xml
const studioXmlPath = 'app/src/main/res/layout/activity_customization_studio.xml';
let studioXml = fs.readFileSync(studioXmlPath, 'utf8');
if (!studioXml.includes('id="@+id/swatchRed"')) {
  studioXml = studioXml.replace(
    '<View\r\n                            android:id="@+id/swatchGold"',
    `<View\r\n                            android:id="@+id/swatchRed"\r\n                            android:layout_width="26dp"\r\n                            android:layout_height="26dp"\r\n                            android:background="@drawable/bg_jarvis_mic_center"\r\n                            android:backgroundTint="#FF4D5E"\r\n                            android:layout_marginEnd="10dp" />\r\n\r\n                        <View\r\n                            android:id="@+id/swatchGold"`
  );
  if (!studioXml.includes('id="@+id/swatchRed"')) {
    studioXml = studioXml.replace(
      '<View\n                            android:id="@+id/swatchGold"',
      `<View\n                            android:id="@+id/swatchRed"\n                            android:layout_width="26dp"\n                            android:layout_height="26dp"\n                            android:background="@drawable/bg_jarvis_mic_center"\n                            android:backgroundTint="#FF4D5E"\n                            android:layout_marginEnd="10dp" />\n\n                        <View\n                            android:id="@+id/swatchGold"`
    );
  }
  fs.writeFileSync(studioXmlPath, studioXml, 'utf8');
  console.log('Added swatchRed to activity_customization_studio.xml');
}

// 2. bottom_sheet_theme_selection.xml
const bottomSheetXmlPath = 'app/src/main/res/layout/bottom_sheet_theme_selection.xml';
let bsXml = fs.readFileSync(bottomSheetXmlPath, 'utf8');
if (!bsXml.includes('id="@+id/swatchRed"')) {
  bsXml = bsXml.replace(
    '<View\r\n                        android:id="@+id/swatch1"',
    `<View\r\n                        android:id="@+id/swatchRed"\r\n                        android:layout_width="32dp"\r\n                        android:layout_height="32dp"\r\n                        android:layout_margin="4dp"\r\n                        android:background="@drawable/bg_jarvis_chip"\r\n                        android:backgroundTint="#FF4D5E" />\r\n\r\n                    <View\r\n                        android:id="@+id/swatch1"`
  );
  if (!bsXml.includes('id="@+id/swatchRed"')) {
    bsXml = bsXml.replace(
      '<View\n                        android:id="@+id/swatch1"',
      `<View\n                        android:id="@+id/swatchRed"\n                        android:layout_width="32dp"\n                        android:layout_height="32dp"\n                        android:layout_margin="4dp"\n                        android:background="@drawable/bg_jarvis_chip"\n                        android:backgroundTint="#FF4D5E" />\n\n                    <View\n                        android:id="@+id/swatch1"`
    );
  }
  fs.writeFileSync(bottomSheetXmlPath, bsXml, 'utf8');
  console.log('Added swatchRed to bottom_sheet_theme_selection.xml');
}
