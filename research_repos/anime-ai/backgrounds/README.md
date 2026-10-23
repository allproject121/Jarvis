# Video Backgrounds

## Sử dụng Video Background

Đặt các file video của bạn vào thư mục này để sử dụng làm background cho sân khấu.

### Định dạng hỗ trợ
- **MP4** (H.264 codec) - Khuyến nghị
- **WebM** (VP8/VP9 codec)
- **OGG** (Theora codec)

### Khuyến nghị kỹ thuật
- **Độ phân giải**: 1920x1080 (Full HD)
- **Tỷ lệ khung hình**: 16:9
- **Frame rate**: 30fps hoặc 60fps
- **Bitrate**: 5-10 Mbps
- **Codec**: H.264 (MP4) hoặc VP9 (WebM)

### Cách sử dụng

#### 1. Qua File Input (Drag & Drop)
1. Mở `humanoid-animations/live.html` hoặc `stage-test.html`
2. Chọn **Backdrop** → **🎬 Video Background**
3. Click vào ô file input và chọn video từ máy tính
4. Video sẽ tự động phát làm background

#### 2. Qua Code
```javascript
// Trong main.js hoặc console
stage.setup({
    background: 'video',
    videoSrc: './backgrounds/my-video.mp4'
});

// Hoặc đổi video sau khi setup
stage.setVideoSource('./backgrounds/another-video.mp4');
```

### Ví dụ video backgrounds
- Concert stage loops
- Cyberpunk cityscapes
- Space nebulas
- Animated LED walls
- Particle effects
- Abstract art animations

### Nguồn video miễn phí
- [Pexels Videos](https://www.pexels.com/videos/)
- [Pixabay Videos](https://pixabay.com/videos/)
- [Coverr](https://coverr.co/)
- [Videvo](https://www.videvo.net/)

### Tối ưu hóa
Nếu video quá nặng, bạn có thể compress bằng:
```bash
# Dùng ffmpeg để compress
ffmpeg -i input.mp4 -c:v libx264 -crf 23 -preset medium -c:a aac -b:a 128k output.mp4
```

### Lưu ý
- Video sẽ tự động loop (phát lặp lại)
- Video sẽ tự mute để hỗ trợ autoplay
- Video hiển thị ở plane lớn phía sau sân khấu (40x22.5 units)
- File quá lớn có thể làm giảm performance
