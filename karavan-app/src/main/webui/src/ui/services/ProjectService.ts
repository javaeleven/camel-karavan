/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {KaravanApi} from '@api/KaravanApi';
import {CamelStatus, ContainerImage, ContainerStatus, DeploymentStatus, Project, ProjectFile, ProjectType, ServiceStatus} from '@models/ProjectModels';
import {TemplateApi} from '@core/api/TemplateApi';
import {InfrastructureAPI} from '@features/project/designer/utils/InfrastructureAPI';
import {unstable_batchedUpdates} from 'react-dom'
import {useAppConfigStore, useDevModeStore, useFilesStore, useFileStore, useProjectsStore, useProjectStore, useStatusesStore} from '@stores/ProjectStore';
import {DebugExchangeData, useDesignerStore} from '@features/project/designer/DesignerStore';
import {ProjectEventBus} from '@bus/ProjectEventBus';
import {EventBus} from "@features/project/designer/utils/EventBus";
import {KameletApi} from "@core/api/KameletApi";
import {getCurrentUser} from "@api/auth/AuthApi";
import {AxiosResponse} from "axios";
import {JSON_SCHEMA_EXTENSION, KARAVAN_DOT_EXTENSION, OPENAPI_FILE_NAME_JSON} from "@core/contants";
import {useLogStore} from "@stores/LogStore";
import {useContainerStatusesStore} from "@stores/ContainerStatusesStore";

export class ProjectService {

    public static startDevModeContainer(projectId: string, verbose: boolean, compile: boolean = false, showLog: boolean = true) {
        useDevModeStore.setState({status: 'wip'})
        KaravanApi.startDevModeContainer(projectId, verbose, compile, res => {
            useDevModeStore.setState({status: 'none'})
            if (res.status === 200 || res.status === 201) {
                ProjectEventBus.sendLog('set', '');
                useLogStore.setState({podName: res.data})
                // NOTE: do NOT switch tabIndex — the 'log' main tab is gone (logs live
                // in the bottom console, which auto-opens on devModeIsRunning) and an
                // unknown tabIndex blanks the main panel.
            } else {
                var resData = (res as any)?.response?.data;
                var error = resData?.message ? resData?.message : res.statusText;
                EventBus.sendAlert('Error Starting DevMode container', error, 'warning')
            }
        });
    }

    public static reloadDevModeCode(project: Project) {
        useDevModeStore.setState({status: 'wip'})
        KaravanApi.reloadDevModeCode(project.projectId, res => {
            useDevModeStore.setState({status: 'none'})
            if (res.status === 200 || res.status === 201) {
                // setIsReloadingPod(false);
            } else {
                EventBus.sendAlert('Error Reloading DevMode container', res.statusText, 'warning')
            }
        });
    }

    public static stopDevModeContainer(projectId: string) {
        useDevModeStore.setState({status: 'wip'})
        KaravanApi.manageContainer(projectId, 'devmode', projectId, 'stop', 'never', res => {
            useDevModeStore.setState({status: 'none'})
            if (res.status === 200) {
            } else {
                EventBus.sendAlert('Error stopping DevMode container', res.statusText, 'warning')
            }
        });
    }

    public static pauseDevModeContainer(project: Project) {
        useDevModeStore.setState({status: 'wip'})
        KaravanApi.manageContainer(project.projectId, 'devmode', project.projectId, 'pause', 'never', res => {
            useDevModeStore.setState({status: 'none'})
            if (res.status === 200) {
            } else {
                EventBus.sendAlert('Error stopping DevMode container', res.statusText, 'warning')
            }
        });
    }

    public static deleteDevModeContainer(projectId: string) {
        useDevModeStore.setState({status: 'wip'})
        ProjectEventBus.sendLog('set', '');
        KaravanApi.deleteDevModeContainer(projectId, false, res => {
            useDevModeStore.setState({status: 'none'})
            if (res.status === 202) {
            } else {
                EventBus.sendAlert('Error delete runner', res.statusText, 'warning')
            }
        });
    }

    public static pushProject(project: Project, commitMessage: string, selectedFileNames: string[]) {
        const params = {
            'projectId': project.projectId,
            'message': commitMessage,
            'userId': getCurrentUser()?.username,
            'fileNames': selectedFileNames.join(","),
        };
        KaravanApi.push(params, res => {
            if (res.status === 200 || res.status === 201) {
                // ProjectService.refreshProject(project.projectId);
                // ProjectService.refreshProjectData(project.projectId);
            } else {
                EventBus.sendAlert("Error pushing", (res as any)?.response?.data, 'danger')
            }
        });
    }

    public static pullProject(projectId: string) {
        useProjectStore.setState({isPulling: true})
        KaravanApi.pull(projectId, res => {
            if (res.status === 200 || res.status === 201) {
                useProjectStore.setState({isPulling: false})
                ProjectService.refreshProject(projectId);
                ProjectService.refreshProjectData(projectId);
            } else {
                EventBus.sendAlert("Error pulling", (res as any)?.response?.data, 'danger')
            }
            useProjectStore.setState({isPulling: false})
        });
    }

    static afterKameletsLoad(yamls: string, saveCamelKamelets: (kameletYamls: string[], clean?: boolean) => void): void {
        try {
            const kamelets: string[] = [];
            yamls.split(/\n?---\n?/).map(c => c.trim()).forEach(z => kamelets.push(z));
            saveCamelKamelets(kamelets, true);
        } catch (e: any) {
            console.error(e);
            EventBus.sendAlert("Error updating kamelets", e?.message, 'danger');
        }
    }

    public static loadCamelAndCustomKamelets() {
        KaravanApi.getCamelKamelets(yaml => ProjectService.afterKameletsLoad(yaml, KameletApi.saveCamelKamelets));
        ProjectService.loadCustomKamelets();
    }

    public static loadCustomKamelets() {
        KaravanApi.getCustomKamelets(yaml => ProjectService.afterKameletsLoad(yaml, KameletApi.saveCustomKamelets));
    }

    public static updateFile(file: ProjectFile, active: boolean, updateFilesStore: boolean = true, after?: (res: AxiosResponse<any>) => void) {
        KaravanApi.putProjectFile(file, res => {
            if (res.status === 200) {
                const newFile = res.data;
                if (updateFilesStore) {
                    useFilesStore.getState().upsertFile(newFile);
                }
                if (active) {
                    useFileStore.setState({file: newFile});
                }
                after?.(res)
            } else {
                EventBus.sendAlert('Error saving file', res.statusText, 'warning')
            }
        })
    }

    public static renameFile(projectId: string, oldName: string, newName: string,  after: (res: boolean) => void) {
        KaravanApi.renameProjectFile(projectId, oldName, newName, (result: boolean, err?: Error | undefined) => {
            if (result) {
                after(result);
            } else {
                EventBus.sendAlert("Error", err?.message ?? "Error copying file!", "warning");
            }
        })
    }

    public static refreshProject(projectId: string) {
        KaravanApi.getProject(projectId, (project: Project) => {
            useProjectStore.setState({project: project});
            unstable_batchedUpdates(() => {
                useProjectsStore.getState().upsertProject(project);
            })
        });
    }

    public static refreshProjects() {
        KaravanApi.getProjects((projects: Project[]) => {
            useProjectsStore.setState({projects: projects});
        });
    }

    public static refreshAllContainerStatuses() {
        KaravanApi.getAllContainerStatuses((statuses: ContainerStatus[]) => {
            useContainerStatusesStore.setState({containers: statuses});
        });
    }

    public static refreshContainerStatus(projectId: string, env: string) {
        KaravanApi.getContainerStatus(projectId, env, (res) => {
            if (res.status === 200) {
                const oldContainers = [...useContainerStatusesStore.getState().containers];
                const newContainers = res.data;
                const newMap = new Map<string, ContainerStatus>(
                    newContainers.map(container => [container.containerName, container])
                );
                const containers = oldContainers
                    .filter(container => newMap.has(container.containerName))  // Filter out old containers not in new
                    .map(container => newMap.get(container.containerName)!)     // Replace with new containers
                    .concat(newContainers.filter(container => !oldContainers.some(old => old.containerName === container.containerName)));
                useContainerStatusesStore.setState({containers: containers});
            }
        })
    }

    public static refreshAllServicesStatuses() {
        KaravanApi.getAllServiceStatuses((statuses: ServiceStatus[]) => {
            useStatusesStore.setState({services: statuses});
        });
    }

    public static refreshAllCamelContextStatuses() {
        KaravanApi.getAllCamelStatuses("context",(statuses: CamelStatus[]) => {
            useStatusesStore.setState({camelContexts: statuses});
        });
    }

    public static refreshAllCamelRouteStatuses() {
        KaravanApi.getAllCamelStatuses("route",(statuses: CamelStatus[]) => {
            useStatusesStore.setState({routes: statuses});
        });
    }

    public static refreshAllCamelProcessorStatuses() {
        KaravanApi.getAllCamelStatuses("processor",(statuses: CamelStatus[]) => {
            useStatusesStore.setState({processors: statuses});
        });
    }
    public static refreshAllCamelConsumerStatuses() {
        KaravanApi.getAllCamelStatuses("consumer",(statuses: CamelStatus[]) => {
            useStatusesStore.setState({consumers: statuses});
        });
    }

    public static refreshCamelStatus(projectId: string, env: string) {
        KaravanApi.getProjectCamelStatuses(projectId, env, (res) => {
            if (res.status === 200) {
                useProjectStore.setState({camelStatuses: res.data})
            } else {
                useProjectStore.setState({camelStatuses: []})
            }
        })
    }

    public static refreshCamelTraces(projectId: string, env: string) {
        KaravanApi.getProjectCamelTraces(projectId, env, res => {
            if (res.status === 200) {
                useProjectStore.setState({camelTraces: res.data})
            } else {
                useProjectStore.setState({camelTraces: []})
            }
        })
    }

    public static startDebugger(projectId: string) {
        const env = useAppConfigStore.getState().config.environment;
        KaravanApi.startDebugger(projectId, env, res => {
            if (res.status >= 200 && res.status < 300) {
                useDesignerStore.getState().setDebugging(true);
            } else {
                EventBus.sendAlert('Error starting debugger', (res as any)?.response?.data || res.statusText, 'warning')
            }
        });
    }

    public static stopDebugger(projectId: string) {
        const env = useAppConfigStore.getState().config.environment;
        KaravanApi.stopDebugger(projectId, env, res => {
            if (!(res.status >= 200 && res.status < 300)) {
                EventBus.sendAlert('Error stopping debugger', (res as any)?.response?.data || res.statusText, 'warning')
            }
            const designerStore = useDesignerStore.getState();
            designerStore.setDebugging(false);
            designerStore.setPassedNodeIds([]);
            designerStore.setSuspendedNodeId(undefined);
            designerStore.setFailed(false);
            designerStore.setBreakpointNodeIds([]);
            designerStore.setDebugExchanges({});
        });
    }

    public static toggleBreakpoint(projectId: string, nodeId: string, isSet: boolean) {
        const env = useAppConfigStore.getState().config.environment;
        const designerStore = useDesignerStore.getState();
        // Optimistic local update so the UI reacts instantly; the next poll of
        // /ui/debug/state reconciles with the backend's breakpoints[] list.
        const current = designerStore.breakpointNodeIds;
        const next = isSet
            ? Array.from(new Set([...current, nodeId]))
            : current.filter(id => id !== nodeId);
        designerStore.setBreakpointNodeIds(next);

        const after = (res: any) => {
            if (!(res.status >= 200 && res.status < 300)) {
                EventBus.sendAlert('Error updating breakpoint', (res as any)?.response?.data || res.statusText, 'warning')
            }
        };
        if (isSet) {
            KaravanApi.addBreakpoint(projectId, env, nodeId, after);
        } else {
            KaravanApi.removeBreakpoint(projectId, env, nodeId, after);
        }
    }

    public static debugStep(projectId: string) {
        const env = useAppConfigStore.getState().config.environment;
        KaravanApi.debugStep(projectId, env, res => {
            if (!(res.status >= 200 && res.status < 300)) {
                EventBus.sendAlert('Error stepping debugger', (res as any)?.response?.data || res.statusText, 'warning')
            }
        });
    }

    public static debugResume(projectId: string) {
        const env = useAppConfigStore.getState().config.environment;
        KaravanApi.debugResume(projectId, env, res => {
            if (!(res.status >= 200 && res.status < 300)) {
                EventBus.sendAlert('Error resuming debugger', (res as any)?.response?.data || res.statusText, 'warning')
            }
        });
    }

    // Best-effort normalizer for Camel's BacklogTracerEventMessage-shaped headers/properties:
    // accepts either an array of {key,type,value} entries or a plain object map, and always
    // returns a plain object so the sidebar can render it uniformly.
    private static normalizeKeyValueList(input: any): Record<string, any> {
        if (input === undefined || input === null) return {};
        if (Array.isArray(input)) {
            const result: Record<string, any> = {};
            input.forEach((entry: any) => {
                if (entry && typeof entry === 'object' && 'key' in entry) {
                    result[entry.key] = 'value' in entry ? entry.value : '';
                }
            });
            return result;
        }
        if (typeof input === 'object') return input;
        return {};
    }

    public static refreshDebugState(projectId: string, env: string) {
        KaravanApi.getDebugState(projectId, env, res => {
            if (res.status !== 200) {
                return;
            }
            // The Camel dev console wraps its payload in a top-level "debug" object, and
            // returns breakpoints as {nodeId, suspended} objects (not plain strings).
            let root: any = res.data;
            if (typeof root === 'string') {
                try { root = JSON.parse(root); } catch (e) { root = {}; }
            }
            const data = (root && root.debug) ? root.debug : (root || {});
            const designerStore = useDesignerStore.getState();

            if (Array.isArray(data.breakpoints)) {
                const ids = data.breakpoints
                    .map((b: any) => (typeof b === 'string' ? b : b?.nodeId))
                    .filter((id: any): id is string => typeof id === 'string');
                designerStore.setBreakpointNodeIds(ids);
            }

            const suspendedList: any[] = Array.isArray(data.suspended) ? data.suspended : [];
            const exchanges: Record<string, DebugExchangeData> = {};
            let anyFailed = false;
            suspendedList.forEach((item: any) => {
                const nodeId: string | undefined = item?.nodeId || item?.toNode;
                if (!nodeId) return;
                const message = item?.message || {};
                const body = message?.body?.value ?? message?.body ?? item?.body;
                const headers = ProjectService.normalizeKeyValueList(message?.headers ?? item?.headers);
                const exchangeProperties = ProjectService.normalizeKeyValueList(message?.exchangeProperties ?? item?.exchangeProperties);
                exchanges[nodeId] = {
                    nodeId: nodeId,
                    exchangeId: item?.exchangeId,
                    body: body,
                    headers: headers,
                    exchangeProperties: exchangeProperties,
                };
                if (item?.failed === true || item?.exception !== undefined || message?.exception !== undefined) {
                    anyFailed = true;
                }
            });

            // Accumulate + dedupe passed nodes: every node we've ever seen suspended at
            // stays highlighted "passed" once execution moves beyond it.
            const previousPassed = designerStore.passedNodeIds;
            const newlyPassed = Object.keys(exchanges).filter(id => !previousPassed.includes(id));
            if (newlyPassed.length > 0) {
                designerStore.setPassedNodeIds([...previousPassed, ...newlyPassed]);
            }

            designerStore.setDebugExchanges({...designerStore.debugExchanges, ...exchanges});
            designerStore.setSuspendedNodeId(suspendedList.length > 0 ? (suspendedList[0]?.nodeId || suspendedList[0]?.toNode) : undefined);
            designerStore.setFailed(anyFailed);
        });
    }

    public static refreshImages(projectId: string) {
        KaravanApi.getImages(projectId, (res: ContainerImage[]) => {
            useProjectStore.setState({images: res});
        });
    }

    public static refreshAllDeploymentStatuses() {
        KaravanApi.getAllDeploymentStatuses((statuses: DeploymentStatus[]) => {
            useStatusesStore.setState({deployments: statuses});
        });
    }

    public static refreshDeploymentStatuses(environment: string) {
        KaravanApi.getDeploymentStatuses(environment, (statuses: DeploymentStatus[]) => {
            useStatusesStore.setState({deployments: statuses});
        });
    }

    public static deleteProject(project: Project, deleteContainers?: boolean) {
        KaravanApi.deleteProject(project, deleteContainers === true, res => {
            if (res.status >= 200 && res.status < 300) {
                EventBus.sendAlert('Success', 'Project deleted', 'success');
                ProjectService.refreshProjects();
            } else {
                // res may be an AxiosError (typed as AxiosResponse here) — surface the
                // backend message from .response.data / .message, falling back to status.
                const err = res as any;
                const reason = err?.response?.data || err?.message || res?.statusText || 'unknown error';
                EventBus.sendAlert('Warning', 'Error when deleting project: ' + reason, 'warning');
            }
        });
    }

    public static deleteFile(file: ProjectFile) {
        KaravanApi.deleteProjectFile(file, res => {
            if (res.status === 204) {
                ProjectService.refreshProjectData(file.projectId);
            } else {
            }
        });
    }

    public static refreshProjectFiles(projectId: string) {
        KaravanApi.getFiles(projectId, (files: ProjectFile[]) => {
            const kameletsYamls = files.filter(f => f.name.endsWith('.kamelet.yaml') && f.code?.length > 0).map(f => f.code).join("\n---\n")
            if (kameletsYamls && kameletsYamls.length > 0) {
                ProjectService.afterKameletsLoad(kameletsYamls, KameletApi.saveProjectKamelets);
            }
            useFilesStore.setState({files: files});
        });

        useFilesStore.getState().fetchCommitedFiles(projectId);

        KaravanApi.getFilesDiff(projectId, (diff: any) => {
            useFilesStore.setState({diff: diff});
        });
    }

    public static refreshProjectData(projectId: string) {
        KaravanApi.getProject(projectId, (project: Project) => {
            // ProjectEventBus.selectProject(project);
            KaravanApi.getFiles(ProjectType.templates, (files: ProjectFile[]) => {
                files.filter(f => f.name.endsWith('java'))
                    .forEach(f => {
                        const name = f.name.replace(".java", '');
                        TemplateApi.saveTemplate(name, f.code);
                    })
            });
        });
        KaravanApi.getCustomKamelets(yaml => ProjectService.afterKameletsLoad(yaml, KameletApi.saveCustomKamelets));
        ProjectService.refreshProjectFiles(projectId);
        KaravanApi.getConfigMaps((any: []) => InfrastructureAPI.setConfigMaps(any));
        KaravanApi.getSecrets((any: []) => InfrastructureAPI.setSecrets(any));
        KaravanApi.getServices((any: []) => InfrastructureAPI.setServices(any));
        KaravanApi.getImages(projectId, (images: []) => useProjectStore.setState({images: images}));
        useFilesStore.getState().fetchCommitedFiles(projectId);
    }

    public static refreshSharedData(after?: (schemaFiles: ProjectFile[], projectOpenFiles: ProjectFile[], sharedFile?: ProjectFile, sharedTemplates?: ProjectFile[]) => void) {
        const projectOpenFilesPromise = new Promise<ProjectFile[]>((resolve) => {
            KaravanApi.getFilesByName(OPENAPI_FILE_NAME_JSON, files => {
                resolve(files);
            });
        });

        const sharedFilesPromise = new Promise<ProjectFile[]>((resolve) => {
            KaravanApi.getFiles(ProjectType.contracts, (files: ProjectFile[]) => {
                resolve(files);
            });
        });

        Promise.all([projectOpenFilesPromise, sharedFilesPromise])
            .then(([projectOpenFiles, sharedFiles]) => {
                const schemaFiles = sharedFiles.filter(f => f.name.endsWith(JSON_SCHEMA_EXTENSION));
                const sharedRouteTemplates = sharedFiles.filter(f => f.name.endsWith(KARAVAN_DOT_EXTENSION.CAMEL_YAML));
                after?.(schemaFiles, projectOpenFiles, undefined, sharedRouteTemplates);
            });
    }
}