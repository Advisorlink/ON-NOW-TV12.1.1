import React from 'react';
import useTouchActivate from '@/hooks/useTouchActivate';

export const PlaybackButton = React.forwardRef(function PlaybackButton({ onClick, ...props }, ref) {
    const activation = useTouchActivate(props.disabled ? undefined : onClick);
    return <button ref={ref} {...props} {...activation} />;
});