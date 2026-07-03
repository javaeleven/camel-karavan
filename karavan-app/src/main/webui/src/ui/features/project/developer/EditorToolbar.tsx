import {Button, Tooltip,} from '@patternfly/react-core';
import '@features/project/designer/karavan.css';
import {useAppConfigStore, useFileStore, useProjectStore} from "@stores/ProjectStore";
import {shallow} from "zustand/shallow";
import {KaravanApi} from "@api/KaravanApi";
import ShareIcon from "@patternfly/react-icons/dist/esm/icons/share-alt-icon";
import {ProjectType} from "@models/ProjectModels";

export function EditorToolbar() {

    const {config} = useAppConfigStore();
    const [project] = useProjectStore((s) => [s.project], shallow)
    const [file] = useFileStore((state) => [state.file], shallow)

    // Run / reload / stop / build actions now live in the bottom console drawer
    // (BottomConsole). The editor toolbar only carries the "Share" action for
    // configuration files.
    const isConfiguration = project.projectId === ProjectType.configuration.toString();
    const isKubernetes = config.infrastructure === 'kubernetes'
    const tooltip = isKubernetes ? "Save as Configmaps" : "Save on shared volume";

    function shareConfigurationFile () {
        if (file) {
            KaravanApi.shareConfigurationFile(file?.name, res => {})
        }
    }

    return (
        <div id="toolbar-group-types">
            <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '8px'}}>
                {isConfiguration &&
                    <Tooltip content={tooltip} position={"bottom-end"}>
                        <Button className="dev-action-button" variant={"primary"} icon={<ShareIcon/>}
                                onClick={e => shareConfigurationFile()}
                        >
                            Share
                        </Button>
                    </Tooltip>
                }
            </div>
        </div>
    )
}
