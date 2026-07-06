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

import {Subject} from "rxjs";
import {unstable_batchedUpdates} from "react-dom";
import {useAppConfigStore, useProjectStore} from "@stores/ProjectStore";
import {ProjectService} from "@services/ProjectService";
import {KaravanApi} from "@api/KaravanApi";
import {EventBus} from "@features/project/designer/utils/EventBus";

export class KaravanEvent {
    id: string = '';
    type: 'system' | 'user' = 'system';
    event: string = '';
    className: string = '';
    data: any = {};

    public constructor(init?: Partial<KaravanEvent>) {
        Object.assign(this, init);
    }
}

const karavanEvents = new Subject<KaravanEvent>();

export const NotificationEventBus = {
    sendEvent: (event: KaravanEvent) =>  karavanEvents.next(event),
    onEvent: () => karavanEvents.asObservable(),
}

// "Build & Rollout": the build pushes the image and applies the manifest, but k8s won't
// restart pods for an unchanged :latest manifest. So when the user asks to roll out, we
// remember the project (-> env) and trigger a rollout once the build's image is loaded.
const pendingRollouts = new Map<string, string>();

export function requestRolloutAfterBuild(projectId: string, environment: string) {
    pendingRollouts.set(projectId, environment);
}

// Trailing-edge debounce for the realtime status push. The backend emits
// 'containersUpdated'/'deploymentsUpdated' on every lifecycle change (build pod
// start/finish, rollout replica change, run/stop/delete). Coalesce a burst (e.g. a
// rollout flipping replicas) into a single refresh so the SPA doesn't refresh-storm.
let statusRefreshTimer: ReturnType<typeof setTimeout> | undefined;
const pendingStatusProjectIds = new Set<string>();

function scheduleStatusRefresh(projectId?: string) {
    if (projectId) {
        pendingStatusProjectIds.add(projectId);
    }
    if (statusRefreshTimer) {
        return;
    }
    statusRefreshTimer = setTimeout(() => {
        statusRefreshTimer = undefined;
        const ids = Array.from(pendingStatusProjectIds);
        pendingStatusProjectIds.clear();
        const openProjectId = useProjectStore.getState().project?.projectId;
        const environment = useAppConfigStore.getState().config?.environment;
        unstable_batchedUpdates(() => {
            // Global stores feeding the Containers tab, dashboards and system pages.
            ProjectService.refreshAllContainerStatuses();
            ProjectService.refreshAllDeploymentStatuses();
            // Camel route/context metrics for the open project (mirrors the page poll).
            if (openProjectId && environment && (ids.length === 0 || ids.includes(openProjectId))) {
                useProjectStore.getState().fetchCamelStatuses(openProjectId, environment);
            }
        });
    }, 700);
}

const sub = NotificationEventBus.onEvent()?.subscribe((event: KaravanEvent) => {
    if (event.event === 'configShared') {
        const filename = event.data?.filename ? event.data?.filename : 'all'
        EventBus.sendAlert('Success', 'Configuration shared for ' + filename);
    } else if (event.event === 'commit' && event.className === "ProjectFolder") {
        const projectId = event.data?.projectId;
        const statuses = event.data?.statuses;
        const messages = event.data?.messages;
        const variant = statuses?.filter((s: any) => s !== "OK")?.length > 0 ? 'danger' : 'success';
        const title = variant === 'success' ? 'Commited' : 'Error';
        EventBus.sendAlert(title, messages, variant);
        if (useProjectStore.getState().project?.projectId === projectId) {
            unstable_batchedUpdates(() => {
                useProjectStore.setState({isPushing: false});
                ProjectService.refreshProject(projectId);
                ProjectService.refreshProjectData(projectId);
            });
        }
    } else if (event.event === 'imagesLoaded') {
        const projectId = event.data?.projectId;
        unstable_batchedUpdates(() => {
            ProjectService.refreshImages(projectId);
        });
        // "Build & Rollout": the new image is now available — restart the deployment so
        // the running pods pick it up (k8s won't restart on its own for a :latest tag).
        const rolloutEnv = pendingRollouts.get(projectId);
        if (rolloutEnv !== undefined) {
            pendingRollouts.delete(projectId);
            KaravanApi.rolloutDeployment(projectId, rolloutEnv, () => {
                EventBus.sendAlert('Rollout', 'Rolling out new image for ' + projectId, 'info');
            });
        }
    } else if (event.event === 'containersUpdated' || event.event === 'deploymentsUpdated') {
        // Realtime push from the k8s informers (via NOTIFICATION_STATUS_UPDATED).
        // Refresh container + deployment + camel statuses immediately instead of
        // waiting for the next poll tick, so build/deploy/run/stop reflect at once.
        scheduleStatusRefresh(event.data?.projectId);
    } else if (event.event === 'reconnected') {
        // The system SSE stream was re-established after a drop (server restart,
        // proxy idle-timeout, network blip). Any events sent meanwhile are lost —
        // resync statuses and the open project's data so the UI catches up
        // without a page reload.
        scheduleStatusRefresh();
        const openProjectId = useProjectStore.getState().project?.projectId;
        if (openProjectId) {
            ProjectService.refreshProjectData(openProjectId);
        }
    } else if (event.event === 'error') {
        const error = event.data?.error;
        EventBus.sendAlert('Error', error, "danger");
    } else if (event.event === 'ping') {
        // do nothing
    } else {
        const message = event.data?.message ?  event.data?.message : JSON.stringify(event.data);
        // EventBus.sendAlert('Success', message);
    }
});

