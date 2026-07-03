import {lazy, Suspense, useEffect, useState} from 'react';
import {shallow} from "zustand/shallow";
import {Bullseye, Spinner} from "@patternfly/react-core";
import {useFilesStore, useProjectStore} from '@stores/ProjectStore';
import {useSelectorStore} from '@features/project/designer/DesignerStore';
import {ProjectService} from '@services/ProjectService';
import {BUILD_IN_PROJECTS, ProjectType} from '@models/ProjectModels';
// Lazy-loaded: the react-topology graph stack (react-topology + dagre + elk, ~1.9MB)
// is the single biggest non-editor dependency and is only needed on the architecture
// tab — code-split it out of the initial app bundle.
const TopologyTab = lazy(() => import('@features/project/project-topology/TopologyTab')
    .then(m => ({default: m.TopologyTab})));
import {CreateProjectModal} from '@features/project/files/CreateProjectModal';
import {DslSelector} from '@features/project/designer/selector/DslSelector';
import {BeanWizard} from '@features/project/beans/BeanWizard';
import {ReadmeTab} from "@features/project/readme/ReadmeTab";
import {SourcesTab} from '@features/project/files/SourcesTab';
import {useProjectFunctions} from "@features/project/ProjectContext";
import {ProjectContainersContextProvider} from "@features/project/ProjectContainersContextProvider";
import {ContainersTab} from "@features/project/project-containers/ContainersTab";
import {PodTab} from "@features/project/project-pod/PodTab";
import "./ProjectPanel.css"

export function ProjectPanel() {

    const [project, tab, setTabIndex] = useProjectStore((s) => [s.project, s.tabIndex, s.setTabIndex], shallow);
    const [setFiles, setSelectedFileNames] = useFilesStore((s) => [s.setFiles, s.setSelectedFileNames], shallow);
    const [showSelector] = useSelectorStore((s) => [s.showSelector], shallow)
    const [asyncApiJson] = useState<string>('');

    const {createNewRouteFile, refreshSharedData} = useProjectFunctions();

    useEffect(() => {
        onRefresh();
    }, [project]);

    function onRefresh() {
        if (project?.projectId) {
            setFiles([]);
            setSelectedFileNames([]);
            ProjectService.refreshProjectData(project.projectId);
            setTabIndex(project.type !== ProjectType.integration ? 'source' : tab);
            refreshSharedData();
        }
    }

    function isBuildIn(): boolean {
        return BUILD_IN_PROJECTS.includes(project.projectId);
    }

    const buildIn = isBuildIn();
    const isTopology = tab === 'architecture';

    return isTopology
        ? (<div className="project-architecture-page">
                <Suspense fallback={<Bullseye><Spinner aria-label="Loading topology"/></Bullseye>}>
                    <TopologyTab asyncApiJson={asyncApiJson}/>
                </Suspense>
                <CreateProjectModal/>
                {showSelector && <DslSelector onDslSelect={createNewRouteFile} showFileNameInput={true}/>}
                <BeanWizard/>
            </div>
        )
        : (
            <div style={{display: 'flex', flexDirection: 'column', height: '100%'}}>
                {/* The dev (execution) + build logs now live in the bottom console
                    drawer, so the old full-panel 'log' / 'build' tabs are gone — they
                    would render a second log viewer redundant with the console. */}
                {tab === 'source' && <SourcesTab/>}
                {!buildIn && tab === 'readme' && <ReadmeTab/>}
                {!buildIn && tab === 'pod' && <ProjectContainersContextProvider><PodTab/></ProjectContainersContextProvider>}
                {!buildIn && tab === 'containers' && <ProjectContainersContextProvider><ContainersTab/></ProjectContainersContextProvider>}
            </div>
        )
}

