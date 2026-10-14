/**
 * 全局浏览器环境声明（WikiKernel 前端脚本）。
 * 这些脚本以普通 <script> 加载，主要通过 DOM 事件产生副作用，并不导出 ES 模块成员。
 */
export {};

declare global {
  interface Window {
    WIKI_MESSAGES?: Record<string, string>;
    SETUP_MESSAGES?: Record<string, string>;
    WIKI_CONFIG?: {
      multiUpload?: boolean;
      graph?: boolean | Record<string, unknown>;
      math?: boolean | Record<string, unknown>;
      mediaViewer?: boolean;
      [key: string]: unknown;
    };
    SETUP_CORE_COMPLETED?: boolean;
    RLCONF?: Record<string, unknown>;
    RLQ?: Array<() => void>;
    wgMessages?: Record<string, string>;
    mw?: WikiKernelMw;
    wikiEditDraft?: unknown;
    mwPopups?: unknown;
    flashPlayer?: unknown;
    graphRender?: (...args: unknown[]) => unknown;
    mathRender?: (...args: unknown[]) => unknown;
    RufflePlayer?: { newest(): unknown; [key: string]: unknown };
    vegaEmbed?: (...args: unknown[]) => Promise<unknown>;
    katex?: { render(tex: string, element: HTMLElement, options?: Record<string, unknown>): void; [key: string]: unknown };
    jQuery?: unknown;
    // @ts-ignore
    requestIdleCallback?: (callback: IdleRequestCallback, options?: IdleRequestOptions) => number;
  }

  interface WikiKernelMw {
    config?: {
      get(name: string): unknown;
      set(name: string, value: unknown): void;
      [key: string]: unknown;
    };
    messages?: Record<string, string>;
    message?: (key: string, ...params: unknown[]) => unknown;
    hook?: (name: string) => {
      add(callback: (...args: unknown[]) => void): unknown;
      fire(...args: unknown[]): unknown;
      [key: string]: unknown;
    };
    loader?: {
      register?(name: string, version?: string, dependencies?: string[]): unknown;
      implement?(name: string, script?: string, style?: string, messages?: Record<string, string>): unknown;
      load?(name: string): Promise<unknown> | unknown;
      using?(modules: string | string[]): Promise<unknown>;
      require?(name: string): unknown;
      getState?(name: string): string | undefined;
      getVersion?(name: string): string | undefined;
      addStyleTag?(css: string): unknown;
      unsupported?(): string[];
      state?: Record<string, unknown>;
      [key: string]: unknown;
    };
    util?: Record<string, (...args: any[]) => any>;
    [key: string]: unknown;
  }
}
