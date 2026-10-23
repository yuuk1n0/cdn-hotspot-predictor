DELETE FROM top50_prediction;

INSERT INTO top50_prediction (
    prediction_date, app_id, granularity, model_version, rank_no, file_key, file_type,
    file_size_bytes, predicted_bytes, prediction_score, recommendation_reason,
    recent_actual_bytes, compression_status
) VALUES
('2026-10-18', '10066', 'DAILY', 'last_day_v1', 1, 'video/course/launch-4k.mp4', 'VIDEO', 734003200, 68000000000, 0.9820, '昨日流量排名第一且连续三日增长', 61200000000, 'PENDING'),
('2026-10-18', '10066', 'DAILY', 'last_day_v1', 2, 'images/campaign/autumn-hero.webp', 'IMAGE', 8388608, 52200000000, 0.9610, '活动素材访问量稳定处于高位', 49800000000, 'PROCESSING'),
('2026-10-18', '10066', 'DAILY', 'last_day_v1', 3, 'audio/podcast/episode-42.aac', 'AUDIO', 125829120, 41800000000, 0.9340, '最近两日播放量持续上升', 38600000000, 'PENDING'),
('2026-10-18', '10066', 'DAILY', 'last_day_v1', 4, 'video/live/replay-final.mp4', 'VIDEO', 524288000, 33700000000, 0.9120, '回放文件在晚高峰重复访问', 30100000000, 'COMPRESSED'),
('2026-10-18', '10066', 'DAILY', 'last_day_v1', 5, 'packages/client/windows-x64.zip', 'OTHER', 419430400, 28600000000, 0.8870, '客户端发布带来集中下载', 25900000000, 'PENDING'),
('2026-10-18', '10066', 'DAILY', 'last_day_v1', 6, 'images/docs/diagram-network.png', 'IMAGE', 6291456, 22100000000, 0.8610, '文档入口曝光推动图片访问', 20500000000, 'PROCESSING'),
('2026-10-18', '10066', 'DAILY', 'last_day_v1', 7, 'video/tutorial/chapter-07.mp4', 'VIDEO', 367001600, 17400000000, 0.8320, '教程章节近七日保持稳定热度', 16900000000, 'PENDING'),
('2026-10-18', '10066', 'DAILY', 'last_day_v1', 8, 'assets/fonts/product-icons.woff2', 'OTHER', 3145728, 12800000000, 0.8040, '多页面公共资源重复请求', 12100000000, 'COMPRESSED'),

('2026-10-18', '10066', 'WEEKLY', 'rolling_7d_v1', 1, 'video/course/launch-4k.mp4', 'VIDEO', 734003200, 428000000000, 0.9760, '近七日流量均值与趋势同时领先', 61200000000, 'PENDING'),
('2026-10-18', '10066', 'WEEKLY', 'rolling_7d_v1', 2, 'packages/client/windows-x64.zip', 'OTHER', 419430400, 316000000000, 0.9480, '版本发布窗口预计持续一周', 25900000000, 'PENDING'),
('2026-10-18', '10066', 'WEEKLY', 'rolling_7d_v1', 3, 'video/live/replay-final.mp4', 'VIDEO', 524288000, 275000000000, 0.9210, '赛事回放周内仍有长尾访问', 30100000000, 'COMPRESSED'),
('2026-10-18', '10066', 'WEEKLY', 'rolling_7d_v1', 4, 'audio/podcast/episode-42.aac', 'AUDIO', 125829120, 233000000000, 0.8990, '订阅推送带来稳定连续播放', 38600000000, 'PROCESSING'),
('2026-10-18', '10066', 'WEEKLY', 'rolling_7d_v1', 5, 'images/campaign/autumn-hero.webp', 'IMAGE', 8388608, 187000000000, 0.8750, '活动周期覆盖完整预测窗口', 49800000000, 'PENDING'),
('2026-10-18', '10066', 'WEEKLY', 'rolling_7d_v1', 6, 'video/tutorial/chapter-07.mp4', 'VIDEO', 367001600, 143000000000, 0.8460, '课程学习进度形成稳定长尾', 16900000000, 'PENDING'),

('2026-10-19', '10066', 'DAILY', 'ewma_v1', 1, 'video/course/launch-4k.mp4', 'VIDEO', 734003200, 72100000000, 0.9860, '指数加权趋势显示热度继续上升', 66800000000, 'PENDING'),
('2026-10-19', '10066', 'DAILY', 'ewma_v1', 2, 'video/tutorial/chapter-07.mp4', 'VIDEO', 367001600, 46800000000, 0.9520, '近期权重提高后排名明显上升', 39200000000, 'PROCESSING'),
('2026-10-19', '10066', 'DAILY', 'ewma_v1', 3, 'images/campaign/autumn-hero.webp', 'IMAGE', 8388608, 39900000000, 0.9270, '活动页流量仍高于历史均值', 44100000000, 'PENDING'),
('2026-10-19', '10066', 'DAILY', 'ewma_v1', 4, 'audio/podcast/episode-43.aac', 'AUDIO', 132120576, 32400000000, 0.9030, '新一期内容首日增长较快', 28700000000, 'PENDING'),
('2026-10-19', '10066', 'DAILY', 'ewma_v1', 5, 'packages/client/macos-arm64.dmg', 'OTHER', 398458880, 24700000000, 0.8710, 'Mac 客户端更新下载集中', 21900000000, 'COMPRESSED'),
('2026-10-19', '10066', 'DAILY', 'ewma_v1', 6, 'images/docs/diagram-network.png', 'IMAGE', 6291456, 18100000000, 0.8390, '文档资源近期访问保持稳定', 17600000000, 'PROCESSING'),

('2026-10-19', '10350', 'DAILY', 'last_day_v1', 1, 'media/product/demo-main.mp4', 'VIDEO', 629145600, 58400000000, 0.9790, '产品首页视频昨日访问最高', 55300000000, 'PENDING'),
('2026-10-19', '10350', 'DAILY', 'last_day_v1', 2, 'images/catalog/device-front.avif', 'IMAGE', 7340032, 43700000000, 0.9470, '新品页面图片曝光快速增长', 40800000000, 'PENDING'),
('2026-10-19', '10350', 'DAILY', 'last_day_v1', 3, 'downloads/firmware/device-v8.bin', 'OTHER', 268435456, 35200000000, 0.9190, '固件升级带来集中下载需求', 33700000000, 'PROCESSING'),
('2026-10-19', '10350', 'DAILY', 'last_day_v1', 4, 'media/events/keynote-replay.mp4', 'VIDEO', 891289600, 29400000000, 0.8910, '发布会回放仍处于传播周期', 28100000000, 'COMPRESSED'),
('2026-10-19', '10350', 'DAILY', 'last_day_v1', 5, 'audio/guide/setup-guide.mp3', 'AUDIO', 94371840, 20300000000, 0.8580, '新设备激活推动指引播放', 19100000000, 'PENDING'),
('2026-10-19', '10350', 'DAILY', 'last_day_v1', 6, 'images/support/error-code-map.png', 'IMAGE', 5242880, 14700000000, 0.8240, '支持页面访问量连续两日增长', 13600000000, 'PROCESSING'),

('2026-10-20', '10350', 'WEEKLY', 'rolling_7d_v1', 1, 'media/product/demo-main.mp4', 'VIDEO', 629145600, 389000000000, 0.9830, '产品演示视频周趋势持续领先', 57100000000, 'PENDING'),
('2026-10-20', '10350', 'WEEKLY', 'rolling_7d_v1', 2, 'downloads/firmware/device-v8.bin', 'OTHER', 268435456, 302000000000, 0.9540, '固件灰度范围扩大形成持续下载', 34900000000, 'PROCESSING'),
('2026-10-20', '10350', 'WEEKLY', 'rolling_7d_v1', 3, 'media/events/keynote-replay.mp4', 'VIDEO', 891289600, 246000000000, 0.9260, '回放长尾预计覆盖整个预测窗口', 27600000000, 'COMPRESSED'),
('2026-10-20', '10350', 'WEEKLY', 'rolling_7d_v1', 4, 'images/catalog/device-front.avif', 'IMAGE', 7340032, 218000000000, 0.9010, '新品活动仍在投放周期内', 42100000000, 'PENDING'),
('2026-10-20', '10350', 'WEEKLY', 'rolling_7d_v1', 5, 'audio/guide/setup-guide.mp3', 'AUDIO', 94371840, 151000000000, 0.8740, '装机指引随设备出货稳定增长', 19800000000, 'PENDING'),
('2026-10-20', '10350', 'WEEKLY', 'rolling_7d_v1', 6, 'images/support/error-code-map.png', 'IMAGE', 5242880, 99000000000, 0.8420, '支持资源近七日访问稳定', 14200000000, 'PROCESSING');
