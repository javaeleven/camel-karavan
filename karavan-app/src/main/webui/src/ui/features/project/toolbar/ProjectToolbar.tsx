import {SettingsToolbar} from "@features/projects/SettingsToolbar";
import {useAppConfigStore, useFileStore, useProjectStore} from "@stores/ProjectStore";
import {shallow} from "zustand/shallow";
import {EditorToolbar} from "@features/project/developer/EditorToolbar";
import {BUILD_IN_PROJECTS} from "@models/ProjectModels";

export function ProjectToolbar() {

    const [project, tabIndex] = useProjectStore((s) => [s.project, s.tabIndex], shallow)
    const [file, operation] = useFileStore((state) => [state.file, state.operation], shallow)
    const [config] = useAppConfigStore((s) => [s.config], shallow);
    const isDev = config.environment === 'dev';
    const isBuildInProject = BUILD_IN_PROJECTS.includes(project?.projectId);
    // Dev-mode + build actions moved to the bottom console drawer (BottomConsole).
    // The top toolbar now only carries project settings (and the editor toolbar
    // when a file is open).
    const showResourceToolbar = (isBuildInProject || !isDev) && tabIndex !== "build";

    function isFile(): boolean {
        return file !== undefined && operation !== 'delete';
    }

    function getProjectToolbar() {
        return (
            <div id="toolbar-group-types" className='main-toolbar-toolbar'>
                {showResourceToolbar && <SettingsToolbar/>}
            </div>
        )
    }

    return isFile() ? <EditorToolbar/> : getProjectToolbar();
}
