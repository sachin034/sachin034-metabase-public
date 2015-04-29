'use strict';
/*global cx, CardRenderer*/

var QueryVisualization = React.createClass({
    displayName: 'QueryVisualization',
    propTypes: {
        card: React.PropTypes.object.isRequired,
        result: React.PropTypes.object,
        setDisplayFn: React.PropTypes.func.isRequired
    },
    getInitialState: function () {
        return {
            chartId: Math.floor((Math.random() * 698754) + 1)
        };
    },
    componentDidMount: function () {
        this.renderChartIfNeeded();
    },
    componentDidUpdate: function () {
        this.renderChartIfNeeded();
    },
    isTableDisplay: function (display) {
        return (display === "table" || display === "scalar");
    },
    renderChartIfNeeded: function () {
        if (!this.isTableDisplay(this.props.card.display) && this.props.result) {
            // TODO: it would be nicer if this didn't require the whole card
            CardRenderer[this.props.card.display](this.state.chartId, this.props.card, this.props.result.data);
        }
    },
    setDisplay: function (event) {
        // notify our parent about our change
        this.props.setDisplayFn(event.target.value);
    },
    renderChartVisualization: function () {
        // rendering a chart of some type
        var titleId = 'card-title--'+this.state.chartId;
        var innerId = 'card-inner--'+this.state.chartId;

        return (
            <div className="Card--{this.props.card.display} Card-outer px1" id={this.state.chartId}>
                <div id={titleId} className="text-centered"></div>
                <div id={innerId} className="card-inner"></div>
            </div>
        );
    },
    renderVizControls: function () {
        if (this.props.result.error === undefined) {
            var types = [
                'table',
                'line',
                'bar',
                'pie',
                'area',
                'timeseries'
            ];

            var displayOptions = [];
            for (var i = 0; i < types.length; i++) {
                var val = types[i];
                displayOptions.push(
                    <option key={i} value={val}>{val}</option>
                );
            };

            return (
                <div className="VisualizationSettings">
                    Show as:
                    <label className="Select">
                        <select onChange={this.setDisplay}>
                            {displayOptions}
                        </select>
                    </label>
                </div>
            );
        } else {
            return false;
        }
    },
    render: function () {
        if(!this.props.result) {
            return false;
        }

        var viz;
        if(this.props.result.error) {
            viz = (
                <p>{this.props.result.error}</p>
            );

        } else if(this.props.result.data) {
            if(this.isTableDisplay(this.props.card.display)) {
                viz = (
                    <QueryVisualizationTable data={this.props.result.data} />
                );
            } else {
                // assume that anything not a table is a chart
                viz = this.renderChartVisualization();
            }
        }



        return (
            <div className="full flex flex-column">
                <div className="Visualization full flex-full">
                    {viz}
                </div>
                {this.renderVizControls()}
            </div>
        );
    }
});
