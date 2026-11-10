export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };
export interface StorageSnapshot { values: Record<string, JsonValue>; revision: string }
export interface PluginStorage {
  read(keys?: string[]): Promise<StorageSnapshot>;
  write(changes: { set?: Record<string, JsonValue>; remove?: string[] }, options?: { expectedRevision?: string }): Promise<{ revision: string }>;
}
export interface HttpRequest {
  url: string;
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE" | "HEAD";
  headers?: Record<string, string>;
  authorization?: { type: "bearer"; token: string } | { type: "basic"; username: string; password: string };
  cookies?: { name: string; value: string }[];
  body?: { type: "json"; value: JsonValue } | { type: "text"; value: string; contentType?: string } | { type: "form"; values: Record<string, string> };
  timeoutMillis?: number;
  redirect?: "manual" | "follow";
}
export interface HttpResponse { status: number; headers: Record<string, string[]>; body: string; url: string; redirectUrl?: string }
export interface SettingsClient {
  readonly storage: { open(namespace: string): PluginStorage };
  readonly http: { request(request: HttpRequest): Promise<HttpResponse> };
}
/** Call once in a top-level settings page; Host failures reject with a stable `code` property. */
export function connectSettings(): Promise<SettingsClient>;
