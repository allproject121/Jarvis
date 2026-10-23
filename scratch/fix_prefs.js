const fs = require('fs');
let xml = fs.readFileSync('scratch/jarvis_prefs.xml', 'utf8');
xml = xml.replace('builtin_classic_orb', 'builtin_hinata_anime');
xml = xml.replace('<string name="key_orb_entity_type">ai_girl</string>', '<string name="key_orb_entity_type">hinata_anime</string>');
xml = xml.replace('<string name="key_orb_entity_type">orb</string>', '<string name="key_orb_entity_type">hinata_anime</string>');
xml = xml.replace(/name="key_orb_scale" value="[^"]*"/, 'name="key_orb_scale" value="1.0"');
fs.writeFileSync('scratch/jarvis_prefs.xml', xml, 'utf8');
console.log('Prefs updated successfully.');
