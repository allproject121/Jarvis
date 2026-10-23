# 📥 Download Animations từ Mixamo

## Danh sách cần download

### ✅ Đã có trong project:
- [x] HipHopDancing.fbx
- [x] Waving.fbx
- [x] Breathing Idle.fbx
- [x] Writing.fbx
- [x] Breakdance Freeze Var 3.fbx (rename → BreakdanceFreeze.fbx)
- [x] Flair.fbx

### ❌ Cần download thêm:

#### Dance Animations (Priority High)
1. **Northern Soul Floor Combo** → `NorthernSoulFloorCombo.fbx`

#### Reactions (Priority Medium)
2. **Excited** → `Excited.fbx`
3. **Clapping** → `Clapping.fbx`
4. **Laughing** → `Laughing.fbx`
5. **Thinking** → `Thinking.fbx`
6. **Crying** → `Crying.fbx`
7. **Defeated** → `Defeated.fbx`

#### Gestures (Priority Medium)
8. **Bowing** → `Bowing.fbx`
9. **Thank You** → `ThankYou.fbx`
10. **Surprised** → `Surprised.fbx`
11. **Shocked** → `Shocked.fbx`

#### Special (Priority Low)
12. **Heart Gesture** (might need alternative)
13. **Blowing Kiss** (might need alternative)
14. **Idle** → `Idle.fbx` (basic standing idle)

---

## 🎯 Workflow nhanh (5-10 phút)

### Setup một lần:
1. Truy cập: https://www.mixamo.com
2. Login/Sign up (Adobe account)
3. Có sẵn character mặc định

### Download mỗi animation (30 giây/animation):

```bash
# Tab 1: Search animation
1. Search tên (vd: "Northern Soul Floor Combo")
2. Click animation → preview

# Tab 2: Download settings
3. Click "Download"
4. Format: FBX Binary (.fbx) ✅
5. Skin: Without Skin ⚠️ QUAN TRỌNG
6. FPS: 30
7. Keyframe Reduction: none
8. Click "Download"

# Tab 3: Rename & move
9. Rename file theo danh sách trên
10. Move vào: ~/Desktop/anime-ai/humanoid-animations/
```

### ⚠️ QUAN TRỌNG - Download Settings:

```
Format: FBX Binary (.fbx)
Skin: Without Skin  ← PHẢI CHỌN NÀY!
Frames per second: 30
Keyframe Reduction: none
```

**Without Skin** = Chỉ animation, không có mesh → Compatible với VRM!

---

## 🤖 Semi-Automated (Dùng Browser Console)

Mở Mixamo → F12 Console → Paste script này:

```javascript
// List animations cần download
const animations = [
  'Northern Soul Floor Combo',
  'Excited',
  'Clapping',
  'Laughing',
  'Thinking',
  'Crying',
  'Defeated',
  'Bowing',
  'Thank You',
  'Surprised',
  'Shocked',
  'Idle'
];

// Copy list to clipboard
copy(animations.join('\n'));
console.log('✅ Copied to clipboard! Paste vào notepad để track progress.');
```

---

## 📋 Alternative Names (nếu không tìm thấy)

Một số animations có tên khác trên Mixamo:

| Cần tìm | Alternative |
|---------|-------------|
| Heart Gesture | Blow Kiss, Air Kiss, Love Gesture |
| Blowing Kiss | Blow A Kiss, Kiss |
| Thank You | Thankful, Grateful, Bowing |
| Shocked | Surprise, Startled |

---

## 🎬 Batch Download Tips

### Mở nhiều tabs cùng lúc:
1. Tab 1-3: Search & preview animations
2. Click download trên mỗi tab
3. Set cùng settings
4. Download cùng lúc

### Browser Settings:
```
Chrome → Settings → Downloads
✅ Ask where to save each file before downloading
→ Chọn thư mục humanoid-animations/ một lần
→ Các file sau tự động vào đó
```

---

## ✅ Quick Check Script

Sau khi download xong, chạy script này để check:

```bash
cd ~/Desktop/anime-ai/humanoid-animations
ls -1 *.fbx | sort
```

Expected output:
```
Bowing.fbx
BreakdanceFreeze.fbx
Breathing Idle.fbx
Clapping.fbx
Crying.fbx
Defeated.fbx
Excited.fbx
Flair.fbx
HipHopDancing.fbx
Idle.fbx
Laughing.fbx
NorthernSoulFloorCombo.fbx
Shocked.fbx
Surprised.fbx
ThankYou.fbx
Thinking.fbx
Waving.fbx
Writing.fbx
```

---

## 🚀 Rename files nhanh

Nếu download với tên khác, rename hàng loạt:

```bash
cd ~/Desktop/anime-ai/humanoid-animations

# Example: Rename "Breakdance Freeze Var 3.fbx" → "BreakdanceFreeze.fbx"
mv "Breakdance Freeze Var 3.fbx" "BreakdanceFreeze.fbx"

# Batch rename (nếu có spaces)
for file in *.fbx; do
  # Remove spaces
  newname=$(echo "$file" | tr -d ' ')
  if [ "$file" != "$newname" ]; then
    mv "$file" "$newname"
  fi
done
```

---

## 💡 Pro Tips

1. **Search chính xác**: Gõ đúng tên để tìm nhanh
2. **Sort by Popular**: Animation phổ biến thường tốt hơn
3. **Preview trước**: Đảm bảo animation phù hợp
4. **Download settings cố định**: Save 1 lần, dùng mãi
5. **Group download**: Download cùng category cùng lúc

---

## 🎯 Priority Order

Nếu không có thời gian download hết:

**Must have (5 files):**
- NorthernSoulFloorCombo.fbx
- Excited.fbx
- Clapping.fbx
- Thinking.fbx
- Bowing.fbx

**Should have (5 files):**
- Laughing.fbx
- Crying.fbx
- Surprised.fbx
- ThankYou.fbx
- Idle.fbx

**Nice to have (3 files):**
- Defeated.fbx
- Shocked.fbx
- HeartGesture.fbx / BlowingKiss.fbx

---

## ⏱️ Time Estimate

- Setup: 2 phút
- Per animation: 30-45 giây
- 12 animations: ~10 phút
- Rename & organize: 2 phút

**Total: ~15 phút** ⚡

Happy downloading! 🎉
