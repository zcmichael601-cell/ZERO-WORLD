export async function readEvents(response, onEvent) {
  if (!response.body) throw new Error("当前浏览器不支持流式响应，请使用较新的浏览器。");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      let boundary;
      while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
        const frame = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        let event = "message";
        const data = [];
        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith("event:")) event = line.slice(6).trim();
          if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
        }
        if (data.length && onEvent(event, data.join("\n"))) return;
      }
      if (done) throw new Error("连接已中断，回复可能不完整，请重新发送。");
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
