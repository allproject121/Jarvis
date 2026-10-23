#!/bin/bash

# Script kiểm tra animations có đủ chưa

cd "$(dirname "$0")/humanoid-animations"

echo "🎭 Checking Animation Files..."
echo ""

# Danh sách cần có
REQUIRED=(
  "Idle.fbx"
  "Waving.fbx"
  "HipHopDancing.fbx"
  "Flair.fbx"
  "NorthernSoulFloorCombo.fbx"
  "BreakdanceFreeze.fbx"
  "Excited.fbx"
  "Clapping.fbx"
  "Laughing.fbx"
  "Thinking.fbx"
  "Crying.fbx"
  "Defeated.fbx"
  "Bowing.fbx"
  "ThankYou.fbx"
  "HeartGesture.fbx"
  "BlowingKiss.fbx"
  "Surprised.fbx"
  "Shocked.fbx"
  "Breathing Idle.fbx"
)

FOUND=0
MISSING=0

echo "✅ Found:"
for file in "${REQUIRED[@]}"; do
  if [ -f "$file" ]; then
    echo "  ✓ $file"
    ((FOUND++))
  fi
done

echo ""
echo "❌ Missing:"
for file in "${REQUIRED[@]}"; do
  if [ ! -f "$file" ]; then
    echo "  ✗ $file"
    ((MISSING++))
  fi
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📊 Summary:"
echo "  Found: $FOUND"
echo "  Missing: $MISSING"
echo "  Total needed: ${#REQUIRED[@]}"
echo ""

if [ $MISSING -eq 0 ]; then
  echo "🎉 All animations ready!"
  echo "🚀 Run: npm run dev"
else
  echo "⚠️  Need to download $MISSING more animations"
  echo "📖 See: download-animations.md"
  echo ""
  echo "Quick download links:"
  echo "  https://www.mixamo.com/#/?page=1&query=excited"
  echo "  https://www.mixamo.com/#/?page=1&query=clapping"
  echo "  https://www.mixamo.com/#/?page=1&query=thinking"
fi

echo ""
echo "💡 Current files in directory:"
ls -1 *.fbx 2>/dev/null | head -20 || echo "  (No .fbx files found)"
