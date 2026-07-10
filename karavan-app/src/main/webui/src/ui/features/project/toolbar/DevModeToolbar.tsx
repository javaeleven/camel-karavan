import React, {useEffect, useState} from 'react';
import {Button, Dropdown, DropdownItem, DropdownList, MenuToggle, MenuToggleElement, Tooltip, TooltipPosition} from '@patternfly/react-core';
import DevIcon from "@patternfly/react-icons/dist/esm/icons/dev-icon";
import ReloadIcon from "@patternfly/react-icons/dist/esm/icons/bolt-icon";
import DeleteIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {useAppConfigStore, useProjectStore} from "@stores/ProjectStore";
import {ProjectService} from "@services/ProjectService";
import {shallow} from "zustand/shallow";
import CompileIcon from "@patternfly/react-icons/dist/esm/icons/code-icon";
import VerboseIcon from "@patternfly/react-icons/dist/esm/icons/list-icon";
import "./DevModeToolbar.css"
import StopIcon from "@patternfly/react-icons/dist/esm/icons/stop-icon";
import {ProjectContainersContext} from "../ProjectContainersContextProvider";
import EllipsisVIcon from "@patternfly/react-icons/dist/esm/icons/ellipsis-v-icon";
import BugIcon from "@patternfly/react-icons/dist/esm/icons/bug-icon";
import StepForwardIcon from "@patternfly/react-icons/dist/esm/icons/step-forward-icon";
import ForwardIcon from "@patternfly/react-icons/dist/esm/icons/forward-icon";
import {useDesignerStore} from "@features/project/designer/DesignerStore";

export function DevModeToolbar() {

    const context = React.useContext(ProjectContainersContext);
    if (!context) throw new Error("ProjectContainersContext not found!");
    const {packagedContainerStatuses, devModeContainerStatus, devModeIsRunning, containerStatuses} = context;

    const [config] = useAppConfigStore((s) => [s.config], shallow);
    const [project, refreshTrace, tabIndex] = useProjectStore((s) => [s.project, s.refreshTrace, s.tabIndex], shallow)
    const [isDebugging] = useDesignerStore((s) => [s.isDebugging], shallow);

    const [showSpinner, setShowSpinner] = useState(false);
    const [reloadAvailable, setReloadAvailable] = useState(false);

    const isKubernetes = config.infrastructure === 'kubernetes'

    const isProjectContainer = packagedContainerStatuses.length > 0;

    const commands = devModeContainerStatus?.commands || ['run'];
    const inTransit = devModeContainerStatus?.inTransit;
    const inDevMode = devModeContainerStatus?.type === 'devmode';
    const isExited = devModeContainerStatus?.state === 'exited';

    useEffect(() => {
        if (showSpinner && hasContainer()) {
            setShowSpinner(false);
        }
    }, [devModeContainerStatus, refreshTrace]);

    // Safety net: clear the spinner if no container appears (e.g. the start failed),
    // so the Run button can't get stuck permanently disabled.
    useEffect(() => {
        if (!showSpinner) {
            return;
        }
        const timeout = setTimeout(() => setShowSpinner(false), 15000);
        return () => clearTimeout(timeout);
    }, [showSpinner]);

    const [isToggleOpen, setIsToggleOpen] = React.useState(false);

    const hasContainer = () => {
        return devModeContainerStatus?.containerId !== undefined && devModeContainerStatus?.containerId !== null
    };

    const onToggleClick = () => {
        setIsToggleOpen(!isToggleOpen);
    };

    const onSelect = (_event: React.MouseEvent<Element, MouseEvent> | undefined, value: string | number | undefined) => {
        // eslint-disable-next-line no-console
        setIsToggleOpen(false);
    };

    function runDevMode(ev: any, verbose: boolean, compile: boolean = false) {
        ev.preventDefault();
        setShowSpinner(true);
        setReloadAvailable(!compile);
        ProjectService.startDevModeContainer(project.projectId, verbose, compile);
    }

    function getRunButton() {
        return (
            <Dropdown
                className="dev-action-button"
                onSelect={onSelect}
                popperProps={{position: 'right'}}
                toggle={(toggleRef: React.Ref<MenuToggleElement>) => (
                    <MenuToggle
                        ref={toggleRef}
                        onClick={onToggleClick}
                        variant="plain"
                        isDisabled={showSpinner}
                        isExpanded={isToggleOpen}
                        aria-label="Action list single group kebab"
                        icon={<EllipsisVIcon/>}
                    />
                )}
                isOpen={isToggleOpen}
                onOpenChange={(isOpen: boolean) => setIsToggleOpen(isOpen)}
            >
                <DropdownList>
                    <DropdownItem value={0} key="verbose" icon={<VerboseIcon/>} onClick={(ev) => runDevMode(ev, true, false)}>
                        Run verbose
                    </DropdownItem>
                    <DropdownItem value={1} key="compile" icon={<CompileIcon/>} onClick={(ev) => runDevMode(ev, false, true)}>
                        Run compile
                    </DropdownItem>
                </DropdownList>
            </Dropdown>
        )
    }

    return (
        <div style={{display: 'flex', flexDirection: 'row', gap: '8px'}}>
            {!devModeIsRunning && !hasContainer() && !isProjectContainer && tabIndex !== "build" &&
                <Tooltip content="Run in Developer mode" position={TooltipPosition.bottomEnd}>
                    {/* Disable + spin immediately on click: the container status that
                        drives inTransit lags, so without this a second click during the
                        latency would start a second dev-mode run. */}
                    <Button className="dev-action-button"
                            isDisabled={inTransit || showSpinner}
                            isLoading={showSpinner}
                            variant={"primary"}
                            icon={showSpinner ? undefined : <DevIcon/>}
                            onClick={(ev) => runDevMode(ev, false, false)}>
                        Run
                    </Button>
                </Tooltip>
            }
            {!devModeIsRunning && !hasContainer() && !isProjectContainer && tabIndex !== "build" && getRunButton()}
            {devModeIsRunning && inDevMode && reloadAvailable &&
                <Tooltip content="Reload" position={TooltipPosition.bottom}>
                    <Button className="project-button dev-action-button"
                            isDisabled={inTransit}
                            variant={"secondary"}
                            icon={<ReloadIcon/>}
                            onClick={() => ProjectService.reloadDevModeCode(project)}>
                        {"Reload"}
                    </Button>
                </Tooltip>
            }
            {devModeIsRunning && inDevMode && !isDebugging &&
                <Tooltip content="Start debugger" position={TooltipPosition.bottom}>
                    <Button className="dev-action-button"
                            isDisabled={inTransit}
                            variant={"secondary"}
                            icon={<BugIcon/>}
                            onClick={() => ProjectService.startDebugger(project.projectId)}>
                        {"Debug"}
                    </Button>
                </Tooltip>
            }
            {devModeIsRunning && inDevMode && isDebugging &&
                <div style={{display: 'flex', flexDirection: 'row', gap: '8px'}}>
                    <Tooltip content="Step" position={TooltipPosition.bottom}>
                        <Button className="dev-action-button"
                                variant={"secondary"}
                                icon={<StepForwardIcon/>}
                                onClick={() => ProjectService.debugStep(project.projectId)}>
                            {"Step"}
                        </Button>
                    </Tooltip>
                    <Tooltip content="Resume" position={TooltipPosition.bottom}>
                        <Button className="dev-action-button"
                                variant={"secondary"}
                                icon={<ForwardIcon/>}
                                onClick={() => ProjectService.debugResume(project.projectId)}>
                            {"Resume"}
                        </Button>
                    </Tooltip>
                    <Tooltip content="Stop debugger" position={TooltipPosition.bottom}>
                        <Button className="dev-action-button"
                                variant={"control"}
                                icon={<BugIcon/>}
                                onClick={() => ProjectService.stopDebugger(project.projectId)}>
                            {"Stop"}
                        </Button>
                    </Tooltip>
                </div>
            }
            {inDevMode && !isKubernetes &&
                <Tooltip content="Stop container" position={TooltipPosition.bottomEnd}>
                    <Button className="dev-action-button"
                            isDisabled={!commands.includes('stop') || inTransit}
                            variant={"control"}
                            icon={<StopIcon/>}
                            onClick={() => {
                                setShowSpinner(true);
                                ProjectService.stopDevModeContainer(project.projectId);
                            }}>
                    </Button>
                </Tooltip>
            }
            {inDevMode &&
                <Tooltip content="Delete container" position={TooltipPosition.bottomEnd}>
                    <Button className="dev-action-button"
                            variant={"control"}
                            icon={<DeleteIcon/>}
                            onClick={() => {
                                setShowSpinner(true);
                                ProjectService.deleteDevModeContainer(project.projectId);
                            }}>
                    </Button>
                </Tooltip>
            }
        </div>);
}
