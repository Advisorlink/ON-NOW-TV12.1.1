import React from 'react';
import useTouchActivate from '@/hooks/useTouchActivate';

export const PlaybackButton = React.forwardRef(function PlaybackButton({ onClick, ...props }, ref) {
    const activation = useTouchActivate(props.disabled ? undefined : (event) => {
        window.__vesperLastPlaybackActivation = Date.now();
        const button = event.currentTarget;
        if (button) {
            const previous = Number(button.getAttribute('data-native-activation-version')) || 0;
            button.setAttribute('data-native-activation-version', String(previous + 1));
        }
        onClick?.(event);
    });
    return <button ref={ref} {...props} data-native-activation="playback" {...activation} />;
});