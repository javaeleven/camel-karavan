export interface ProjectInfo {
    projectId: string;
    isDevModeRunning: boolean;
    isPackagedRunning: boolean;
    isBuildRunning: boolean;
    routes: RouteComponentsInfo[];
    exposesOpenApi: boolean;
    implementsAsyncApi: boolean;
}

export interface RouteComponentsInfo {
    routeId: string;
    nodePrefixId: string;
    routeTemplateRef: string;
    isTemplated: boolean;
    fileName: string;
    consumers: ComponentInfo[];
    producers: ComponentInfo[];
}

export interface ComponentInfo {
    id: string;
    name: string;
    parameters: Record<string, string>;
}

export interface OperationStatistic {
    action: string;
    protocol: string;
    address: string;
    total: number;
    inflight: number;
    failed: number;
    projectId?: string;
}

export const HTTP_METHODS_LOWERCASE: string[] = [
    'get',
    'post',
    'put',
    'patch',
    'delete',
    'head'
]

export const HTTP_METHODS: string[] = HTTP_METHODS_LOWERCASE.map(m => m.toUpperCase());