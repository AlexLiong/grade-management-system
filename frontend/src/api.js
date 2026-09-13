export async function api(path, body) {
  const csrf =
    document.cookie
      .split("; ")
      .find((x) => x.startsWith("CAMPUS_CSRF="))
      ?.slice(12) || "";
  const response = await fetch("/api" + path, {
    method: body === undefined ? "GET" : "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", "X-CSRF-Token": csrf },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const result = await response.json();
  if (!response.ok) {
    const error = new Error(result.message || "请求失败");
    error.status = response.status;
    throw error;
  }
  return result;
}
