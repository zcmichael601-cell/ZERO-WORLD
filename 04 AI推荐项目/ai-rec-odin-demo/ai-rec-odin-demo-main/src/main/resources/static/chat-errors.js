export async function responseError(response) {
  const summary = `请求失败（HTTP ${response.status}${response.statusText ? ` ${response.statusText}` : ""}）`;
  let details;
  try {
    details = (await response.text()).trim();
  } catch {
    return new Error(`${summary}\n无法读取错误响应，请查看服务端日志。`);
  }
  try {
    const payload = JSON.parse(details);
    details = typeof payload === "string" ? payload : JSON.stringify(payload, null, 2);
  } catch {
  }
  details = details.replace(/(Bearer\s+)[^\s"',;<>]+/gi, "$1[REDACTED]")
    .replace(/((?:api[-_]?key|access[-_]?token|password|secret)"?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;&}]+)/gi, "$1[REDACTED]");
  if (details.length > 8000) details = `${details.slice(0, 8000)}\n（详情过长，完整响应请查看服务端日志）`;
  return new Error(`${summary}\n${details || "服务端未返回错误详情，请查看服务端日志。"}`);
}
