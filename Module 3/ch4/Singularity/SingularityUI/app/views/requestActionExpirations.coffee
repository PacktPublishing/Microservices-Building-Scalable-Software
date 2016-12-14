View = require './view'

class requestActionExpirations extends View

    template: require '../templates/requestDetail/requestActionExpirations'

    initialize: ({@model}) ->
        @listenTo @model, 'change', @render
        @listenTo @model, 'sync', @render

    render: =>
        return if not @model.synced
        @$el.html @template @renderData()

    renderData: =>
        expirations = []
        request = @model.toJSON()

        alertSkipHealthchecks = request.request.skipHealthchecks

        if request.expiringScale and (request.expiringScale.startMillis + request.expiringScale.expiringAPIRequestObject.durationMillis) > new Date().getTime()
            expirations.push
                action: "Scale (to #{request.request.instances} instances)"
                user: if request.expiringScale.user then request.expiringScale.user.split('@')[0] else ""
                endMillis: request.expiringScale.startMillis + request.expiringScale.expiringAPIRequestObject.durationMillis
                canRevert: true
                cancelText: 'Make Permanent'
                cancelAction: "makeScalePermanent"
                revertText: "Revert to #{request.expiringScale.revertToInstances} #{if request.expiringScale.revertToInstances is 1 then 'instance' else 'instances'}"
                revertAction: 'revertScale'
                revertParam: request.expiringScale.revertToInstances
                message: request.expiringScale.expiringAPIRequestObject.message

        if request.expiringBounce
            if request.expiringBounce.expiringAPIRequestObject.durationMillis
                endMillis = request.expiringBounce.startMillis + request.expiringBounce.expiringAPIRequestObject.durationMillis
            else
                endMillis = request.expiringBounce.startMillis + (config.defaultBounceExpirationMinutes * 60 * 1000)
            if endMillis > new Date().getTime()
                expirations.push
                    action: 'Bounce'
                    user: if request.expiringBounce.user then request.expiringBounce.user.split('@')[0] else ""
                    endMillis: endMillis
                    canRevert: false
                    cancelText: 'Cancel'
                    cancelAction: 'cancelBounce'
                    message: request.expiringBounce.expiringAPIRequestObject.message

        if request.expiringPause and (request.expiringPause.startMillis + request.expiringPause.expiringAPIRequestObject.durationMillis) > new Date().getTime()
            expirations.push
                action: 'Pause'
                user: if request.expiringPause.user then request.expiringPause.user.split('@')[0] else ""
                endMillis: request.expiringPause.startMillis + request.expiringPause.expiringAPIRequestObject.durationMillis
                canRevert: true
                cancelText: 'Make Permanent'
                cancelAction: 'makePausePermanent'
                revertText: "Unpause"
                revertAction: 'revertPause'
                message: request.expiringPause.expiringAPIRequestObject.message

        if request.expiringSkipHealthchecks and (request.expiringSkipHealthchecks.startMillis + request.expiringSkipHealthchecks.expiringAPIRequestObject.durationMillis) > new Date().getTime()
            alertSkipHealthchecks = false
            expirations.push
                action: if request.expiringSkipHealthchecks.expiringAPIRequestObject.skipHealthchecks then 'Disable Healthchecks' else 'Enable Healthchecks'
                user: if request.expiringSkipHealthchecks.user then request.expiringSkipHealthchecks.user.split('@')[0] else ""
                endMillis: request.expiringSkipHealthchecks.startMillis + request.expiringSkipHealthchecks.expiringAPIRequestObject.durationMillis
                canRevert: true
                cancelText: 'Make Permanent'
                cancelAction: 'makeSkipHealthchecksPermanent'
                revertText: if request.expiringSkipHealthchecks.expiringAPIRequestObject.skipHealthchecks then 'Enable Healthchecks' else 'Disable Healthchecks'
                revertAction: 'revertSkipHealthchecks'
                revertParam: !request.expiringSkipHealthchecks.expiringAPIRequestObject.skipHealthchecks
                message: request.expiringSkipHealthchecks.expiringAPIRequestObject.message

        request: request
        data: expirations
        alertSkipHealthchecks: alertSkipHealthchecks

module.exports = requestActionExpirations
