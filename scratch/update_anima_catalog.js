const fs = require('fs');
let s = fs.readFileSync('research_repos/Anima/frontend/src/characters/catalog.ts', 'utf8');
const entry = `  {
    id: 'hinata',
    label: 'Hinata',
    tagline: 'Gentle, shy, deeply loyal anime companion',
    greeting: "Ano... Hello! I-I'm glad you're here. How can I help you?",
    modelUrl: '/models/hinata.vrm',
    preview: '/characters/hinata.png',
    tint: '#e9e3f5',
    preferences: { idle: 6, doze: 0.1 },
  },
`;
s = s.replace('export const CHARACTER_OPTIONS = [', 'export const CHARACTER_OPTIONS = [\n' + entry);
fs.writeFileSync('research_repos/Anima/frontend/src/characters/catalog.ts', s, 'utf8');
console.log('Updated catalog.ts successfully');
