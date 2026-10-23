const fs = require('fs');
let xml = fs.readFileSync('app/src/main/res/layout/activity_customization_studio.xml', 'utf8');

// 1. Remove duplicate entity row in Tab 2
const dupEntityPattern = /<TextView[\r\n\s]+android:layout_width="wrap_content"[\r\n\s]+android:layout_height="wrap_content"[\r\n\s]+android:text="AI COMPANION ENTITY FORM"[\s\S]*?<\/LinearLayout>[\r\n\s]+<!-- 3D MODEL & COMPANION VAULT GALLERY/;

if (!dupEntityPattern.test(xml)) {
  console.log('dupEntityPattern did not match');
} else {
  xml = xml.replace(dupEntityPattern, '<!-- 3D MODEL & COMPANION VAULT GALLERY');
  console.log('Removed duplicate entity row in Tab 2');
}

// 2. Remove duplicate layoutCustom3dMediaControls in Tab 2
const dupMediaPattern = /<!-- Custom 3D Avatar Universal Media & Real 3D Model Controls -->[\r\n\s]+<LinearLayout[\r\n\s]+android:id="@\+id\/layoutCustom3dMediaControls"[\s\S]*?<\/LinearLayout>[\r\n\s]+<\/LinearLayout>[\r\n\s]+<!-- Live Interaction Showcase Card -->/;

if (!dupMediaPattern.test(xml)) {
  console.log('dupMediaPattern did not match');
} else {
  xml = xml.replace(dupMediaPattern, '<!-- Live Interaction Showcase Card -->');
  console.log('Removed duplicate layoutCustom3dMediaControls in Tab 2');
}

fs.writeFileSync('app/src/main/res/layout/activity_customization_studio.xml', xml, 'utf8');
console.log('Done!');
