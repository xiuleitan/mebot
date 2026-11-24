# 日程卡片配图

以下六张内置预览图最初使用内置 imagegen 工具生成，未使用 CLI 或外部图片服务。每张配图独立生成，原始输出保留在 Codex 默认生成目录；应用使用的副本位于 `app/src/main/res/drawable-nodpi/`，经 WebP 编码为 640 × 640，六张合计约 232 KiB。渐变、模糊、完成态变灰由 Compose 实时绘制，未烘焙到原图。

以下为实际使用的完整提示词（`transparent_background=false`）。

## schedule_meeting.webp

应用路径：`app/src/main/res/drawable-nodpi/schedule_meeting.webp`。

```text
Use case: photorealistic-natural. Asset type: square photo background for a small Android schedule card. Primary request: A bright modern meeting room with an open unbranded laptop on a pale conference table, grey upholstered chairs, large windows and one leafy indoor plant; airy neutral daylight. Style: natural editorial photography, realistic materials, calm refined everyday setting, shallow depth of field. Composition: square, recognizable main subject in upper two thirds, generous crop-safe margins. Constraints: one continuous full-bleed photograph; no text, no letters, no numbers, no logos, no watermark, no interface, no borders, no collage. Full color, soft contrast. Do not add a gradient or text panel; these are applied by the app.
```

## schedule_medical.webp

应用路径：`app/src/main/res/drawable-nodpi/schedule_medical.webp`。

```text
Use case: photorealistic-natural. Asset type: square photo background for a small Android schedule card. Primary request: A real stethoscope with dark teal tubing resting on a clean white desk in a bright medical office, leafy plant and soft window light in background; no people. Style: natural editorial photography, realistic materials, calm refined everyday setting, shallow depth of field. Composition: square, recognizable main subject in upper two thirds, generous crop-safe margins. Constraints: one continuous full-bleed photograph; no text, no letters, no numbers, no logos, no watermark, no interface, no borders, no collage. Full color, soft contrast. Do not add a gradient or text panel; these are applied by the app.
```

## schedule_review.webp

应用路径：`app/src/main/res/drawable-nodpi/schedule_review.webp`。

```text
Use case: photorealistic-natural. Asset type: square photo background for a small Android schedule card. Primary request: An open unbranded laptop, a notebook and a black pen on a warm wooden desk beside a small green plant; cozy daylight workspace, no readable screen content. Style: natural editorial photography, realistic materials, calm refined everyday setting, shallow depth of field. Composition: square, recognizable main subject in upper two thirds, generous crop-safe margins. Constraints: one continuous full-bleed photograph; no text, no letters, no numbers, no logos, no watermark, no interface, no borders, no collage. Full color, soft contrast. Do not add a gradient or text panel; these are applied by the app.
```

## schedule_fitness.webp

应用路径：`app/src/main/res/drawable-nodpi/schedule_fitness.webp`。

```text
Use case: photorealistic-natural. Asset type: square photo background for a small Android schedule card. Primary request: A pair of black dumbbells, a clear water bottle and folded white towel on a gym bench; softly blurred bright fitness studio in background, no people. Style: natural editorial photography, realistic materials, calm refined everyday setting, shallow depth of field. Composition: square, recognizable main subject in upper two thirds, generous crop-safe margins. Constraints: one continuous full-bleed photograph; no text, no letters, no numbers, no logos, no watermark, no interface, no borders, no collage. Full color, soft contrast. Do not add a gradient or text panel; these are applied by the app.
```

## schedule_dinner.webp

应用路径：`app/src/main/res/drawable-nodpi/schedule_dinner.webp`。

```text
Use case: photorealistic-natural. Asset type: square photo background for a small Android schedule card. Primary request: A beautifully set restaurant dining table with white plates, two elegant wine glasses, soft candlelight and warm amber bokeh in background; no people. Style: natural editorial photography, realistic materials, calm refined everyday setting, shallow depth of field. Composition: square, recognizable main subject in upper two thirds, generous crop-safe margins. Constraints: one continuous full-bleed photograph; no text, no letters, no numbers, no logos, no watermark, no interface, no borders, no collage. Full color, soft contrast. Do not add a gradient or text panel; these are applied by the app.
```

## schedule_groceries.webp

应用路径：`app/src/main/res/drawable-nodpi/schedule_groceries.webp`。

```text
Use case: photorealistic-natural. Asset type: square photo background for a small Android schedule card. Primary request: A brown paper grocery bag filled with fresh leafy greens, red bell peppers, tomatoes and a baguette on a pale counter; softly blurred bright grocery store in background. Style: natural editorial photography, realistic materials, calm refined everyday setting, shallow depth of field. Composition: square, recognizable main subject in upper two thirds, generous crop-safe margins. Constraints: one continuous full-bleed photograph; no text, no letters, no numbers, no logos, no watermark, no interface, no borders, no collage. Full color, soft contrast. Do not add a gradient or text panel; these are applied by the app.
```


## 运行时自动封面（0.11.0）

新建真实日程无用户图片时，应用根据标题在本地构造以下提示词，调用已配置的 `z-image-turbo` 同步接口，使用 `768*768`、`prompt_extend=false`。只生成一张装饰图，不发送附件或聊天，也不使用内置预览图冒充生成结果。图片下载并验证后保存到手机持久目录，复用现有渐变、模糊和完成态渲染。

```text
为个人日程制作一张正方形装饰封面，主题：${title}。写实静物摄影，自然柔光，简洁干净，主体位于画面上半部，下半部留出柔和背景供界面叠字。只表现活动主题，不包含文字、日期、数字、标志或水印，不推测用户附件内容。
```

封面队列、恢复、用户图片优先级及手机验收见 [日程模块说明](schedule-ui.md#ai-自动封面0110)。
